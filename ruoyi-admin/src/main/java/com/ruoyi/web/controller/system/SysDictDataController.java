package com.ruoyi.web.controller.system;

import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * 数据字典信息
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/system/dict/data")
public class SysDictDataController extends BaseController {
    private final ISysDictDataService dictDataService;

    private final ISysDictTypeService dictTypeService;

    /**
     * 用于敏感字典的按需鉴权（等价于 @PreAuthorize 里的 @ss.hasPermi）
     */
    private final PermissionService permissionService;

    public SysDictDataController(ISysDictDataService dictDataService, ISysDictTypeService dictTypeService,
            PermissionService permissionService) {
        this.dictDataService = dictDataService;
        this.dictTypeService = dictTypeService;
        this.permissionService = permissionService;
    }

    /**
     * 获取字典数据列表
     */
    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysDictData dictData) {
        startPage();
        List<SysDictData> list = dictDataService.selectDictDataList(dictData);
        return getDataTable(list);
    }

    /**
     * 导出字典数据列表
     */
    @Log(title = "字典数据", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('system:dict:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysDictData dictData) {
        List<SysDictData> list = exportLimit(() -> dictDataService.selectDictDataList(dictData));
        ExcelUtil<SysDictData> util = new ExcelUtil<>(SysDictData.class);
        util.exportExcel(response, list, "字典数据");
    }

    /**
     * 查询字典数据详细
     */
    @PreAuthorize("@ss.hasPermi('system:dict:query')")
    @GetMapping(value = "/{dictCode}")
    public AjaxResult getInfo(@PathVariable Long dictCode) {
        return success(dictDataService.selectDictDataById(dictCode));
    }

    /**
     * 根据字典类型查询字典数据（优先走字典缓存，未命中时返回空列表而非 null）
     * <p>
     * 刻意不加 {@code @PreAuthorize}：前端初始化下拉框要批量加载大量字典，
     * 逐个鉴权会把每个下拉框都绑到权限点上，成本高且易漏配。代价是任何登录用户都能
     * 枚举全部字典类型，因此用"敏感字典黑名单"兜底——{@code ruoyi.sensitiveDictTypes}
     * 里登记的字典类型才要求 {@code system:dict:list} 权限，其余保持开放。
     */
    @GetMapping(value = "/type/{dictType}")
    public AjaxResult dictType(@PathVariable String dictType) {
        if (RuoYiConfig.getSensitiveDictTypes().contains(dictType)
                && !permissionService.hasPermi("system:dict:list")) {
            logger.warn("用户[{}]无权限读取敏感字典类型[{}]", getUsername(), dictType);
            return error("字典类型需要权限才能读取：" + dictType);
        }
        List<SysDictData> data = dictTypeService.selectDictDataByType(dictType);
        if (StringUtils.isNull(data)) {
            data = new ArrayList<>();
        }
        return success(data);
    }

    /**
     * 新增字典数据
     */
    @PreAuthorize("@ss.hasPermi('system:dict:add')")
    @Log(title = "字典数据", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysDictData dict) {
        if (!dictDataService.checkDictValueUnique(dict)) {
            return error("新增字典数据'" + dict.getDictLabel() + "'失败，同类型下键值已存在：" + dict.getDictValue());
        }
        dict.setCreateBy(getUsername());
        return toAjax(dictDataService.insertDictData(dict));
    }

    /**
     * 修改保存字典数据
     */
    @PreAuthorize("@ss.hasPermi('system:dict:edit')")
    @Log(title = "字典数据", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysDictData dict) {
        if (!dictDataService.checkDictValueUnique(dict)) {
            return error("修改字典数据'" + dict.getDictLabel() + "'失败，同类型下键值已存在：" + dict.getDictValue());
        }
        dict.setUpdateBy(getUsername());
        return toAjax(dictDataService.updateDictData(dict));
    }

    /**
     * 删除字典数据
     */
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    @Log(title = "字典数据", businessType = BusinessType.DELETE)
    @DeleteMapping("/{dictCodes}")
    public AjaxResult remove(@PathVariable Long[] dictCodes) {
        dictDataService.deleteDictDataByIds(dictCodes);
        return success();
    }
}
