package com.ruoyi.common.utils.poi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多 Sheet 导出时的数据信息
 *
 * 使用示例：
 * <pre>
 *   List<ExcelSheet<?>> sheets = new ArrayList<>();
 *   sheets.add(new ExcelSheet<>("参数数据", configList, Config.class, "参数信息"));
 *   sheets.add(new ExcelSheet<>("岗位数据", postList, Post.class, "岗位信息"));
 *   return ExcelUtil.exportMultiSheet(sheets);
 * </pre>
 * 
 * @author ruoyi
 */
public class ExcelSheet<T> {
    /** Sheet 名称 */
    private final String sheetName;

    /** 导出数据集合（构造完成即定型，对外只暴露只读视图） */
    private final List<T> list;

    /** 数据对应的实体 Class */
    private final Class<T> clazz;

    /** Sheet 顶部大标题（可为空） */
    private final String title;

    public ExcelSheet(String sheetName, List<T> list, Class<T> clazz) {
        this(sheetName, list, clazz, "");
    }

    public ExcelSheet(String sheetName, List<T> list, Class<T> clazz, String title) {
        this.sheetName = sheetName;
        this.list = list != null ? list : new ArrayList<>();
        this.clazz = clazz;
        this.title = title != null ? title : "";
    }

    public String getSheetName() {
        return sheetName;
    }

    public List<T> getList() {
        // 导出为只读场景，返回不可修改视图，防止调用方 sheet.getList().add(...) 绕过构造改动内容
        return Collections.unmodifiableList(list);
    }

    public Class<T> getClazz() {
        return clazz;
    }

    public String getTitle() {
        return title;
    }
}
