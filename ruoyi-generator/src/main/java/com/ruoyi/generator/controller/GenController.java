package com.ruoyi.generator.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.sql.SqlUtil;
import com.ruoyi.generator.config.GenConfig;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.service.IGenTableColumnService;
import com.ruoyi.generator.service.IGenTableService;

/**
 * 代码生成 操作处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/tool/gen")
public class GenController extends BaseController {
    private final IGenTableService genTableService;

    private final IGenTableColumnService genTableColumnService;

    private final GenConfig genConfig;

    public GenController(IGenTableService genTableService, IGenTableColumnService genTableColumnService,
            GenConfig genConfig) {
        this.genTableService = genTableService;
        this.genTableColumnService = genTableColumnService;
        this.genConfig = genConfig;
    }

    /**
     * 查询代码生成列表
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:list')")
    @GetMapping("/list")
    public TableDataInfo genList(GenTable genTable) {
        startPage();
        List<GenTable> list = genTableService.selectGenTableList(genTable);
        return getDataTable(list);
    }

    /**
     * 获取代码生成信息：一次返回表配置(info)+列配置(rows)+全部可关联表(tables)，供编辑页回显与主子表选择
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:query')")
    @GetMapping(value = "/{tableId}")
    public AjaxResult getInfo(@PathVariable Long tableId) {
        GenTable table = genTableService.selectGenTableById(tableId);
        List<GenTable> tables = genTableService.selectGenTableAll();
        List<GenTableColumn> list = genTableColumnService.selectGenTableColumnListByTableId(tableId);
        // 聚合三部分数据：当前表、其列列表、全量表(供选择子表)
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("info", table);
        map.put("rows", list);
        map.put("tables", tables);
        return success(map);
    }

    /**
     * 查询数据库列表
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:list')")
    @GetMapping("/db/list")
    public TableDataInfo dataList(GenTable genTable) {
        startPage();
        List<GenTable> list = genTableService.selectDbTableList(genTable);
        return getDataTable(list);
    }

    /**
     * 查询数据表字段列表
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:list')")
    @GetMapping(value = "/column/{tableId}")
    public TableDataInfo columnList(Long tableId) {
        TableDataInfo dataInfo = new TableDataInfo();
        List<GenTableColumn> list = genTableColumnService.selectGenTableColumnListByTableId(tableId);
        dataInfo.setRows(list);
        dataInfo.setTotal(list.size());
        return dataInfo;
    }

    /**
     * 导入表结构（保存）
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:import')")
    @Log(title = "代码生成", businessType = BusinessType.IMPORT)
    @PostMapping("/importTable")
    public AjaxResult importTableSave(@RequestParam("tables") String tables,
            @RequestParam("tplWebType") String tplWebType) {
        String[] tableNames = Convert.toStrArray(tables);
        if (tableNames == null || tableNames.length == 0) {
            return AjaxResult.error("请选择要导入的表");
        }
        // 查询表信息
        List<GenTable> tableList = genTableService.selectDbTableListByNames(tableNames);
        genTableService.importGenTable(tableList, tplWebType, SecurityUtils.getUsername());
        return success();
    }

    /**
     * 创建表结构（保存）：过滤危险关键字→解析 CREATE 语句→防覆盖校验→执行建表→回读元数据→导入生成配置。
     *
     * <p>
     * 建表（DDL 隐式提交）与导入生成配置（@Transactional）分两阶段处理：若建表已成功而导入失败，
     * 返回信息会带上已建成的表名，便于运维定位"表已建、配置未入库"的不一致态并人工回滚。建表前逐一
     * 校验目标库是否已存在同名实表，存在则拒绝，避免覆盖已有结构。
     * </p>
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:create')")
    @Log(title = "创建表", businessType = BusinessType.OTHER)
    @PostMapping("/createTable")
    public AjaxResult createTableSave(@RequestParam("sql") String sql, @RequestParam("tplWebType") String tplWebType) {
        // 建表成功的名单提前声明，确保兜底 catch 也能带回"已建成"信息
        List<String> createdTables = new ArrayList<>();
        try {
            // 安全前置：过滤 DROP/TRUNCATE 等危险关键字，防止拼接恶意语句
            SqlUtil.filterKeyword(sql);
            // 用 Druid 按 MySQL 方言解析出语句列表，仅收集 CREATE TABLE 语句
            List<SQLStatement> sqlStatements = SQLUtils.parseStatements(sql, DbType.mysql);
            List<MySqlCreateTableStatement> createStatements = new ArrayList<>();
            for (SQLStatement sqlStatement : sqlStatements) {
                if (sqlStatement instanceof MySqlCreateTableStatement createTableStatement) {
                    createStatements.add(createTableStatement);
                }
            }
            if (createStatements.isEmpty()) {
                return AjaxResult.error("未解析到有效的 CREATE TABLE 语句");
            }
            // 防覆盖：建表前逐一校验目标库是否已存在同名实表，存在则拒绝
            for (MySqlCreateTableStatement createTableStatement : createStatements) {
                String tableName = createTableStatement.getTableName().replaceAll("`", "");
                if (genTableService.checkTableExist(tableName)) {
                    return AjaxResult.error("表 '" + tableName + "' 已存在，不允许覆盖创建");
                }
            }
            // 阶段一：逐表执行 DDL 建表并记录成功名单（剔除反引号）
            for (MySqlCreateTableStatement createTableStatement : createStatements) {
                if (genTableService.createTable(createTableStatement.toString())) {
                    createdTables.add(createTableStatement.getTableName().replaceAll("`", ""));
                }
            }
        } catch (Exception e) {
            // 解析/建表阶段异常统一兜底，避免暴露 SQL 细节；若已建部分表则一并带回便于回滚
            logger.error(e.getMessage(), e);
            if (!createdTables.isEmpty()) {
                return AjaxResult.error("创建表结构异常，已成功建表 " + createdTables + "，请人工核对");
            }
            return AjaxResult.error("创建表结构异常");
        }
        if (createdTables.isEmpty()) {
            return AjaxResult.error("创建表结构失败，未成功建立任何表");
        }
        // 阶段二：回读元数据并导入生成配置，独立于 DDL 之外，失败时明确暴露"表已建、配置未入库"的不一致态
        try {
            List<GenTable> tableList = genTableService.selectDbTableListByNames(createdTables.toArray(new String[0]));
            genTableService.importGenTable(tableList, tplWebType, SecurityUtils.getUsername());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return AjaxResult.error("表已建成功 " + createdTables + "，但导入生成配置失败，请人工核对后重试");
        }
        return AjaxResult.success();
    }

    /**
     * 修改保存代码生成业务
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:edit')")
    @Log(title = "代码生成", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult editSave(@Validated @RequestBody GenTable genTable) {
        genTableService.validateEdit(genTable);
        genTableService.updateGenTable(genTable);
        return success();
    }

    /**
     * 删除代码生成
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:remove')")
    @Log(title = "代码生成", businessType = BusinessType.DELETE)
    @DeleteMapping("/{tableIds}")
    public AjaxResult remove(@PathVariable Long[] tableIds) {
        genTableService.deleteGenTableByIds(tableIds);
        return success();
    }

    /**
     * 预览代码
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:preview')")
    @GetMapping("/preview/{tableId}")
    public AjaxResult preview(@PathVariable("tableId") Long tableId) throws IOException {
        Map<String, String> dataMap = genTableService.previewCode(tableId);
        return success(dataMap);
    }

    /**
     * 生成代码（下载方式）
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:code')")
    @Log(title = "代码生成", businessType = BusinessType.GENCODE)
    @GetMapping("/download/{tableName}")
    public void download(HttpServletResponse response, @PathVariable("tableName") String tableName) throws IOException {
        byte[] data = genTableService.downloadCode(tableName);
        writeZipToResponse(response, data);
    }

    /**
     * 生成代码（自定义路径）
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:code')")
    @Log(title = "代码生成", businessType = BusinessType.GENCODE)
    @GetMapping("/genCode/{tableName}")
    public AjaxResult genCode(@PathVariable("tableName") String tableName) {
        if (!genConfig.isAllowOverwrite()) {
            return AjaxResult.error("【系统预设】不允许生成文件覆盖到本地");
        }
        genTableService.generatorCode(tableName);
        return success();
    }

    /**
     * 同步数据库
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:edit')")
    @Log(title = "代码生成", businessType = BusinessType.UPDATE)
    @GetMapping("/synchDb/{tableName}")
    public AjaxResult synchDb(@PathVariable("tableName") String tableName) {
        genTableService.synchDb(tableName);
        return success();
    }

    /**
     * 批量生成代码
     */
    @PreAuthorize("@ss.hasPermi('tool:gen:code')")
    @Log(title = "代码生成", businessType = BusinessType.GENCODE)
    @GetMapping("/batchGenCode")
    public void batchGenCode(HttpServletResponse response, String tables) throws IOException {
        String[] tableNames = Convert.toStrArray(tables);
        if (tableNames == null || tableNames.length == 0) {
            throw new ServiceException("请选择要生成的表");
        }
        byte[] data = genTableService.downloadCode(tableNames);
        writeZipToResponse(response, data);
    }

    /**
     * 生成 zip 文件并写入响应流（统一的下载响应头设置）
     *
     * @param response HTTP 响应，将 zip 字节写入其输出流
     * @param data     已打包的 zip 字节数组
     * @throws IOException 写响应流失败时抛出
     */
    private void writeZipToResponse(HttpServletResponse response, byte[] data) throws IOException {
        // 重置响应，清除可能的缓存/已提交头，保证下载头干净
        response.reset();
        // 允许跨域读取，并暴露 Content-Disposition 供前端取文件名
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.setHeader("Content-Disposition", "attachment; filename=\"ruoyi.zip\"");
        response.addHeader("Content-Length", "" + data.length);
        response.setContentType("application/octet-stream; charset=UTF-8");
        IOUtils.write(data, response.getOutputStream());
    }
}
