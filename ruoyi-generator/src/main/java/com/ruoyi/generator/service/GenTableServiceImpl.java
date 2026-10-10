package com.ruoyi.generator.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.core.text.CharsetKit;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.mapper.GenTableColumnMapper;
import com.ruoyi.generator.mapper.GenTableMapper;
import com.ruoyi.generator.util.GenUtils;
import com.ruoyi.generator.util.VelocityUtils;

/**
 * 业务 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class GenTableServiceImpl implements IGenTableService {
    private static final Logger log = LoggerFactory.getLogger(GenTableServiceImpl.class);

    /** 单次批量生成的表数上限：防止一次传入过多表逐表渲染全量驻留内存 + zip 触发 OOM/超时 */
    private static final int MAX_BATCH_GEN_TABLES = 50;

    private final GenTableMapper genTableMapper;

    private final GenTableColumnMapper genTableColumnMapper;

    private final GenUtils genUtils;

    public GenTableServiceImpl(GenTableMapper genTableMapper, GenTableColumnMapper genTableColumnMapper,
            GenUtils genUtils) {
        this.genTableMapper = genTableMapper;
        this.genTableColumnMapper = genTableColumnMapper;
        this.genUtils = genUtils;
    }

    /**
     * 查询业务信息
     * 
     * @param id 业务ID
     * @return 业务信息
     */
    @Override
    public GenTable selectGenTableById(Long id) {
        GenTable genTable = genTableMapper.selectGenTableById(id);
        if (genTable == null) {
            throw new ServiceException("生成表不存在，ID：" + id);
        }
        setTableFromOptions(genTable);
        return genTable;
    }

    /**
     * 查询已导入的生成表列表
     * 
     * @param genTable 业务信息
     * @return 业务集合
     */
    @Override
    public List<GenTable> selectGenTableList(GenTable genTable) {
        return genTableMapper.selectGenTableList(genTable);
    }

    /**
     * 查询当前库中可导入的实表列表
     * 
     * @param genTable 业务信息
     * @return 数据库表集合
     */
    @Override
    public List<GenTable> selectDbTableList(GenTable genTable) {
        return genTableMapper.selectDbTableList(genTable);
    }

    /**
     * 按表名数组批量查询待导入实表
     * 
     * @param tableNames 表名称组
     * @return 数据库表集合
     */
    @Override
    public List<GenTable> selectDbTableListByNames(String[] tableNames) {
        return genTableMapper.selectDbTableListByNames(tableNames);
    }

    /**
     * 查询所有表信息
     * 
     * @return 表信息集合
     */
    @Override
    public List<GenTable> selectGenTableAll() {
        return genTableMapper.selectGenTableAll();
    }

    /**
     * 修改业务：先将前端 params 聚合为 options JSON 写主表，行更新成功后再逐条同步列配置（事务内）
     * 
     * @param genTable 业务信息
     * @return 结果
     */
    @Override
    @Transactional
    public void updateGenTable(GenTable genTable) {
        // params(Map) → options(JSON 字符串)：树配置/上级菜单等额外选项统一序列化存一列
        String options = JSON.toJSONString(genTable.getParams());
        genTable.setOptions(options);
        int row = genTableMapper.updateGenTable(genTable);
        if (row > 0 && StringUtils.isNotEmpty(genTable.getColumns())) {
            // 主表更新成功后才级联刷列，避免主表失败时列已改的不一致
            Long tableId = genTable.getTableId();
            for (GenTableColumn genTableColumn : genTable.getColumns()) {
                // 归属校验：只允许改本表的列，杜绝把别的表的 columnId 混进 columns 跨表越权改写
                if (genTableColumn.getTableId() == null || !genTableColumn.getTableId().equals(tableId)) {
                    throw new ServiceException("列[" + genTableColumn.getColumnName() + "]不属于当前生成表，禁止跨表修改");
                }
                genTableColumnMapper.updateGenTableColumn(genTableColumn);
            }
        }
    }

    /**
     * 删除业务对象：同时删除主表配置与其列配置（事务保证级联一致）
     * 
     * @param tableIds 需要删除的数据ID
     * @return 结果
     */
    @Override
    @Transactional
    public void deleteGenTableByIds(Long[] tableIds) {
        genTableMapper.deleteGenTableByIds(tableIds);
        genTableColumnMapper.deleteGenTableColumnByTableIds(tableIds);
    }

    /**
     * 创建表：mapper 执行 DDL 返回 0 视为成功
     *
     * @param sql 创建表语句
     * @return 建表是否成功
     */
    @Override
    public boolean createTable(String sql) {
        // execute 对 DDL 语句固定返回 0，== 0 即成功
        return genTableMapper.createTable(sql) == 0;
    }

    /**
     * 校验当前库中是否已存在同名实表：经 information_schema.tables 计数，>0 即存在
     *
     * @param tableName 表名称
     * @return 存在返回 true
     */
    @Override
    public boolean checkTableExist(String tableName) {
        return genTableMapper.checkTableExist(tableName) > 0;
    }

    /** 按表名查询生成表并校验存在：不存在(未导入/表名错)即抛业务异常，杜绝下游解引用 null 触发 500 */
    private GenTable requireTableByName(String tableName) {
        GenTable table = genTableMapper.selectGenTableByName(tableName);
        if (table == null) {
            throw new ServiceException("表不存在或尚未导入：" + tableName);
        }
        return table;
    }

    /**
     * 导入表结构：逐表初始化元数据并写主表，主表写入成功后再从 information_schema 回读列并逐列初始化入库
     * 
     * @param tableList 导入表列表
     */
    @Override
    @Transactional
    public void importGenTable(List<GenTable> tableList, String tplWebType, String operName) {
        try {
            // 一次批量反读所有待导入表的列结构并按表名分组，避免逐表 N+1（导 N 表仅 1 次 information_schema 查询）
            String[] tableNames = tableList.stream().map(GenTable::getTableName).toArray(String[]::new);
            Map<String, List<GenTableColumn>> columnsByTable = tableNames.length == 0 ? Collections.emptyMap()
                    : genTableColumnMapper.selectDbTableColumnsByNames(tableNames).stream()
                            .collect(Collectors.groupingBy(GenTableColumn::getTableName));
            for (GenTable table : tableList) {
                String tableName = table.getTableName();
                table.setTplWebType(tplWebType);
                genUtils.initTable(table, operName);
                int row = genTableMapper.insertGenTable(table);
                if (row > 0) {
                    // 保存列信息：从预取的分组结果中取该表的列，逐列初始化后合并为一条多值 INSERT
                    List<GenTableColumn> genTableColumns = columnsByTable.getOrDefault(tableName,
                            Collections.emptyList());
                    if (StringUtils.isNotEmpty(genTableColumns)) {
                        for (GenTableColumn column : genTableColumns) {
                            GenUtils.initColumnField(column, table);
                        }
                        // 多列合并为一条多值 INSERT，避免逐列 N 次往返
                        genTableColumnMapper.batchInsertGenTableColumn(genTableColumns);
                    }
                }
            }
        } catch (Exception e) {
            log.error("导入表结构失败", e);
            throw new ServiceException("导入失败：" + e.getMessage());
        }
    }

    /**
     * 预览代码
     * 
     * @param tableId 表编号
     * @return 预览数据列表
     */
    @Override
    public Map<String, String> previewCode(Long tableId) {
        Map<String, String> dataMap = new LinkedHashMap<>();
        // 查询表信息（不存在即抛，杜绝下游解引用 null）
        GenTable table = genTableMapper.selectGenTableById(tableId);
        if (table == null) {
            throw new ServiceException("生成表不存在，ID：" + tableId);
        }
        // 设置主子表信息
        setSubTable(table);
        // 设置主键列信息
        setPkColumn(table);

        VelocityContext context = VelocityUtils.prepareContext(table);

        // 获取模板列表
        List<String> templates = VelocityUtils.getTemplateList(table);
        for (String template : templates) {
            // 渲染模板
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, Constants.UTF8);
            tpl.merge(context, sw);
            dataMap.put(template, sw.toString());
        }
        return dataMap;
    }

    /**
     * 生成代码（下载方式）
     * 
     * @param tableName 表名称
     * @return 数据
     */
    @Override
    public byte[] downloadCode(String tableName) {
        return downloadCode(new String[] { tableName });
    }

    /**
     * 生成代码（自定义路径）
     * 
     * @param tableName 表名称
     */
    @Override
    public void generatorCode(String tableName) {
        // 查询表信息（不存在即抛，杜绝下游解引用 null）
        GenTable table = requireTableByName(tableName);
        // 设置主子表信息
        setSubTable(table);
        // 设置主键列信息
        setPkColumn(table);

        VelocityContext context = VelocityUtils.prepareContext(table);

        // 获取模板列表
        List<String> templates = VelocityUtils.getTemplateList(table);
        for (String template : templates) {
            // 跳过 SQL/前端 API/类型/列表页等模板（这些不适合写入本地工程目录，仅供下载/预览）
            if (!StringUtils.containsAny(template, "sql.vm", "api.js.vm", "api.ts.vm", "type.ts.vm", "index.ts.vm",
                    "index.vue.vm", "index-tree.vue.vm", "view.vue.vm")) {
                // 渲染模板
                StringWriter sw = new StringWriter();
                Template tpl = Velocity.getTemplate(template, Constants.UTF8);
                tpl.merge(context, sw);
                try {
                    String path = getGenPath(table, template);
                    FileUtils.writeStringToFile(new File(path), sw.toString(), CharsetKit.UTF_8);
                } catch (IOException e) {
                    // 写盘失败无法降级（用户预期拿到文件），包装为业务异常上抛
                    throw new ServiceException("渲染模板失败，表名：" + table.getTableName());
                }
            }
        }
    }

    /**
     * 同步数据库：以实表为准做增量 diff——新增列插入、已存在列保留人工配置后更新、实表已删的列删除
     * 
     * @param tableName 表名称
     */
    @Override
    @Transactional
    public void synchDb(String tableName) {
        GenTable table = requireTableByName(tableName);
        List<GenTableColumn> tableColumns = table.getColumns();
        Map<String, GenTableColumn> tableColumnMap = tableColumns.stream()
                .collect(Collectors.toMap(GenTableColumn::getColumnName, Function.identity()));

        List<GenTableColumn> dbTableColumns = genTableColumnMapper.selectDbTableColumnsByName(tableName);
        if (StringUtils.isEmpty(dbTableColumns)) {
            throw new ServiceException("同步数据失败，原表结构不存在");
        }
        List<String> dbTableColumnNames = dbTableColumns.stream().map(GenTableColumn::getColumnName)
                .collect(Collectors.toList());

        dbTableColumns.forEach(column -> {
            GenUtils.initColumnField(column, table);
            if (tableColumnMap.containsKey(column.getColumnName())) {
                // 列已存在：沿用旧 columnId 转 UPDATE，并尽量保留用户之前调过的查询/必填等人工配置
                GenTableColumn prevColumn = tableColumnMap.get(column.getColumnName());
                column.setColumnId(prevColumn.getColumnId());
                if (column.isList()) {
                    // 如果是列表，继续保留查询方式/字典类型选项
                    column.setDictType(prevColumn.getDictType());
                    column.setQueryType(prevColumn.getQueryType());
                }
                if (StringUtils.isNotEmpty(prevColumn.getIsRequired()) && !column.isPk()
                        && (column.isInsert() || column.isEdit())
                        && ((column.isUsableColumn()) || (!column.isSuperColumn()))) {
                    // 如果是(新增/修改&非主键/非忽略及父属性)，继续保留必填/显示类型选项
                    column.setIsRequired(prevColumn.getIsRequired());
                    column.setHtmlType(prevColumn.getHtmlType());
                }
                genTableColumnMapper.updateGenTableColumn(column);
            } else {
                // 实表新增的列：之前无配置，直接插入
                genTableColumnMapper.insertGenTableColumn(column);
            }
        });

        // 实表已不存在的旧列：从配置表中删除（按主键 column_id 精删）
        List<Long> delColumnIds = tableColumns.stream()
                .filter(column -> !dbTableColumnNames.contains(column.getColumnName()))
                .map(GenTableColumn::getColumnId).collect(Collectors.toList());
        if (StringUtils.isNotEmpty(delColumnIds)) {
            genTableColumnMapper.deleteGenTableColumns(delColumnIds);
        }
    }

    /**
     * 批量生成代码（下载方式）：逐表渲染写入 zip；index-bak.ts 类型的聚合内容最后一次性补入
     * 
     * @param tableNames 表数组
     * @return zip 字节数据
     */
    @Override
    public byte[] downloadCode(String[] tableNames) {
        if (tableNames == null || tableNames.length == 0) {
            throw new ServiceException("请选择要生成的表");
        }
        // 单次批量上限：避免一次传入过多表全量驻留内存 + zip 触发 OOM/超时
        if (tableNames.length > MAX_BATCH_GEN_TABLES) {
            throw new ServiceException("单次批量生成不得超过 " + MAX_BATCH_GEN_TABLES + " 张表，请分批生成");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(outputStream);
        // typeFiles 缓存需跨表合并的 index-bak.ts（多表 export 行追加到同一文件）
        Map<String, StringBuilder> typeFiles = new HashMap<>();
        // zipFailures 累积写入失败条目：不再静默返回残缺 zip，末尾统一抛出
        List<String> zipFailures = new ArrayList<>();
        for (String tableName : tableNames) {
            generatorCode(tableName, zip, typeFiles, zipFailures);
        }
        // 合并完后再把聚合结果作为一个条目写入 zip
        for (Map.Entry<String, StringBuilder> entry : typeFiles.entrySet()) {
            writeToZip(zip, entry.getKey(), entry.getValue().toString(), zipFailures);
        }
        // closeQuietly 确保 zip 中央目录写入完成（不写则 zip 不可解压）
        IOUtils.closeQuietly(zip);
        if (!zipFailures.isEmpty()) {
            throw new ServiceException("部分文件写入ZIP失败：" + zipFailures);
        }
        return outputStream.toByteArray();
    }

    /**
     * 查询表信息并生成代码
     */
    private void generatorCode(String tableName, ZipOutputStream zip, Map<String, StringBuilder> typeFiles,
            List<String> zipFailures) {
        // 查询表信息（不存在即抛，杜绝下游解引用 null）
        GenTable table = requireTableByName(tableName);
        // 设置主子表信息
        setSubTable(table);
        // 设置主键列信息
        setPkColumn(table);

        VelocityContext context = VelocityUtils.prepareContext(table);

        // 获取模板列表
        List<String> templates = VelocityUtils.getTemplateList(table);
        for (String template : templates) {
            // 渲染模板
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, Constants.UTF8);
            tpl.merge(context, sw);
            String fileName = VelocityUtils.getFileName(template, table);
            // index-bak.ts 模版，追加内容（多表共享同一声明文件，只收集 export * from 行）
            if (fileName.contains("index-bak.ts")) {
                if (!typeFiles.containsKey(fileName)) {
                    // 首次：整份内容作为基线
                    typeFiles.put(fileName, new StringBuilder(sw.toString()));
                } else {
                    // 后续表：仅提取 export * from 行追加，避免重复模板样板内容
                    Arrays.stream(sw.toString().split("\n")).filter(line -> line.startsWith("export * from"))
                            .forEach(line -> typeFiles.get(fileName).append("\n").append(line));
                }
            } else {
                // 其他文件正常添加
                writeToZip(zip, fileName, sw.toString(), zipFailures);
            }
        }
    }

    /**
     * 将字符串内容写入ZIP输出流
     * 
     * @param zip      ZIP输出流
     * @param fileName ZIP条目名称（即文件名）
     * @param content  要写入的内容
     */
    private void writeToZip(ZipOutputStream zip, String fileName, String content, List<String> zipFailures) {
        try {
            zip.putNextEntry(new ZipEntry(fileName));
            IOUtils.write(content, zip, Constants.UTF8);
            zip.flush();
            zip.closeEntry();
        } catch (IOException e) {
            // 不吞异常：记录并累积，由 downloadCode 在关闭 zip 后统一抛出，避免静默返回残缺 zip
            log.error("写入ZIP文件失败，文件名: " + fileName, e);
            zipFailures.add(fileName);
        }
    }

    /**
     * 修改保存参数校验
     * 
     * @param genTable 业务信息
     */
    @Override
    public void validateEdit(GenTable genTable) {
        if (GenConstants.TPL_TREE.equals(genTable.getTplCategory())) {
            String options = JSON.toJSONString(genTable.getParams());
            JSONObject paramsObj = JSON.parseObject(options);
            if (StringUtils.isEmpty(paramsObj.getString(GenConstants.TREE_CODE))) {
                throw new ServiceException("树编码字段不能为空");
            } else if (StringUtils.isEmpty(paramsObj.getString(GenConstants.TREE_PARENT_CODE))) {
                throw new ServiceException("树父编码字段不能为空");
            } else if (StringUtils.isEmpty(paramsObj.getString(GenConstants.TREE_NAME))) {
                throw new ServiceException("树名称字段不能为空");
            }
        } else if (GenConstants.TPL_SUB.equals(genTable.getTplCategory())) {
            if (StringUtils.isEmpty(genTable.getSubTableName())) {
                throw new ServiceException("关联子表的表名不能为空");
            } else if (StringUtils.isEmpty(genTable.getSubTableFkName())) {
                throw new ServiceException("子表关联的外键名不能为空");
            }
        }
    }

    /**
     * 设置主键列信息
     * 
     * @param table 业务表信息
     */
    public void setPkColumn(GenTable table) {
        List<GenTableColumn> columns = table.getColumns();
        if (StringUtils.isEmpty(columns)) {
            throw new ServiceException("表未配置任何列，无法生成：" + table.getTableName());
        }
        for (GenTableColumn column : columns) {
            if (column.isPk()) {
                table.setPkColumn(column);
                break;
            }
        }
        if (StringUtils.isNull(table.getPkColumn())) {
            // 未标记主键时降级取第一列，保证模板渲染不因 pkColumn 为空而 NPE
            table.setPkColumn(columns.get(0));
        }
        if (GenConstants.TPL_SUB.equals(table.getTplCategory())) {
            GenTable subTable = table.getSubTable();
            if (subTable == null || StringUtils.isEmpty(subTable.getColumns())) {
                throw new ServiceException("主子表模板要求子表已导入且含列配置，请检查子表：" + table.getSubTableName());
            }
            for (GenTableColumn column : subTable.getColumns()) {
                if (column.isPk()) {
                    subTable.setPkColumn(column);
                    break;
                }
            }
            if (StringUtils.isNull(subTable.getPkColumn())) {
                subTable.setPkColumn(subTable.getColumns().get(0));
            }
        }
    }

    /**
     * 设置主子表信息
     * 
     * @param table 业务表信息
     */
    public void setSubTable(GenTable table) {
        String subTableName = table.getSubTableName();
        if (StringUtils.isNotEmpty(subTableName)) {
            GenTable subTable = genTableMapper.selectGenTableByName(subTableName);
            if (subTable == null) {
                // 子表名指向未导入的表：及早抛错，杜绝下游 setPkColumn/prepareContext 解引用 null NPE
                throw new ServiceException("关联子表不存在或尚未导入：" + subTableName);
            }
            table.setSubTable(subTable);
        }
    }

    /**
     * 设置代码生成其他选项值
     * 
     * @param genTable 设置后的生成对象
     */
    public void setTableFromOptions(GenTable genTable) {
        JSONObject paramsObj = JSON.parseObject(genTable.getOptions());
        if (StringUtils.isNotNull(paramsObj)) {
            String treeCode = paramsObj.getString(GenConstants.TREE_CODE);
            String treeParentCode = paramsObj.getString(GenConstants.TREE_PARENT_CODE);
            String treeName = paramsObj.getString(GenConstants.TREE_NAME);
            Long parentMenuId = paramsObj.getLongValue(GenConstants.PARENT_MENU_ID);
            String parentMenuName = paramsObj.getString(GenConstants.PARENT_MENU_NAME);
            boolean isView = paramsObj.getBooleanValue(GenConstants.GEN_VIEW);

            genTable.setTreeCode(treeCode);
            genTable.setTreeParentCode(treeParentCode);
            genTable.setTreeName(treeName);
            genTable.setParentMenuId(parentMenuId);
            genTable.setParentMenuName(parentMenuName);
            genTable.setView(isView);
        }
    }

    /**
     * 获取代码生成地址：genPath 为 "/" 时回退到工程目录下的 src，否则直接拼接自定义路径
     * 
     * @param table    业务表信息
     * @param template 模板文件路径
     * @return 生成地址
     */
    public static String getGenPath(GenTable table, String template) {
        String genPath = table.getGenPath();
        // "/" 为默认占位，表示未自定义路径，落到当前工作目录/src
        if (StringUtils.equals(genPath, "/")) {
            return System.getProperty("user.dir") + File.separator + "src" + File.separator
                    + VelocityUtils.getFileName(template, table);
        }
        return genPath + File.separator + VelocityUtils.getFileName(template, table);
    }
}
