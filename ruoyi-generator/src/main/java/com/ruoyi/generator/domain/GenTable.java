package com.ruoyi.generator.domain;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.ArrayUtils;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.utils.StringUtils;

/**
 * 业务表 gen_table
 * 
 * @author ruoyi
 */
public class GenTable extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 编号 */
    private Long tableId;

    /** 表名称 */
    @NotBlank(message = "表名称不能为空")
    private String tableName;

    /** 表描述 */
    @NotBlank(message = "表描述不能为空")
    private String tableComment;

    /** 关联父表的表名 */
    private String subTableName;

    /** 本表关联父表的外键名 */
    private String subTableFkName;

    /** 实体类名称(首字母大写) */
    @NotBlank(message = "实体类名称不能为空")
    private String className;

    /** 使用的模板（crud单表操作 tree树表操作 sub主子表操作） */
    private String tplCategory;

    /** 前端类型（element-ui模版 element-plus模版 element-plus-typescript模版） */
    private String tplWebType;

    /** 生成包路径 */
    @NotBlank(message = "生成包路径不能为空")
    private String packageName;

    /** 生成模块名 */
    @NotBlank(message = "生成模块名不能为空")
    private String moduleName;

    /** 生成业务名 */
    @NotBlank(message = "生成业务名不能为空")
    private String businessName;

    /** 生成功能名 */
    @NotBlank(message = "生成功能名不能为空")
    private String functionName;

    /** 生成作者 */
    @NotBlank(message = "作者不能为空")
    private String functionAuthor;

    /** 表单布局（单列 双列 三列） */
    private Integer formColNum;

    /** 生成代码方式（0zip压缩包 1自定义路径） */
    private String genType;

    /** 生成路径（不填默认项目路径） */
    private String genPath;

    /** 主键信息 */
    private GenTableColumn pkColumn;

    /** 子表信息 */
    private GenTable subTable;

    /** 表列信息 */
    @Valid
    private List<GenTableColumn> columns;

    /** 其它生成选项 */
    private String options;

    /** 树编码字段 */
    private String treeCode;

    /** 树父编码字段 */
    private String treeParentCode;

    /** 树名称字段 */
    private String treeName;

    /** 上级菜单ID字段 */
    private Long parentMenuId;

    /** 上级菜单名称字段 */
    private String parentMenuName;

    /** 是否生成详情页 */
    private boolean isView;

    public Long getTableId() {
        return tableId;
    }

    public void setTableId(Long tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public String getSubTableName() {
        return subTableName;
    }

    public void setSubTableName(String subTableName) {
        this.subTableName = subTableName;
    }

    public String getSubTableFkName() {
        return subTableFkName;
    }

    public void setSubTableFkName(String subTableFkName) {
        this.subTableFkName = subTableFkName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getTplCategory() {
        return tplCategory;
    }

    public void setTplCategory(String tplCategory) {
        this.tplCategory = tplCategory;
    }

    public String getTplWebType() {
        return tplWebType;
    }

    public void setTplWebType(String tplWebType) {
        this.tplWebType = tplWebType;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public String getFunctionAuthor() {
        return functionAuthor;
    }

    public void setFunctionAuthor(String functionAuthor) {
        this.functionAuthor = functionAuthor;
    }

    public Integer getFormColNum() {
        return formColNum;
    }

    public void setFormColNum(Integer formColNum) {
        this.formColNum = formColNum;
    }

    public String getGenType() {
        return genType;
    }

    public void setGenType(String genType) {
        this.genType = genType;
    }

    public String getGenPath() {
        return genPath;
    }

    public void setGenPath(String genPath) {
        this.genPath = genPath;
    }

    public GenTableColumn getPkColumn() {
        return pkColumn;
    }

    public void setPkColumn(GenTableColumn pkColumn) {
        this.pkColumn = pkColumn;
    }

    public GenTable getSubTable() {
        return subTable;
    }

    public void setSubTable(GenTable subTable) {
        this.subTable = subTable;
    }

    public List<GenTableColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<GenTableColumn> columns) {
        this.columns = columns;
    }

    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }

    public String getTreeCode() {
        return treeCode;
    }

    public void setTreeCode(String treeCode) {
        this.treeCode = treeCode;
    }

    public String getTreeParentCode() {
        return treeParentCode;
    }

    public void setTreeParentCode(String treeParentCode) {
        this.treeParentCode = treeParentCode;
    }

    public String getTreeName() {
        return treeName;
    }

    public void setTreeName(String treeName) {
        this.treeName = treeName;
    }

    public Long getParentMenuId() {
        return parentMenuId;
    }

    public void setParentMenuId(Long parentMenuId) {
        this.parentMenuId = parentMenuId;
    }

    public String getParentMenuName() {
        return parentMenuName;
    }

    public void setParentMenuName(String parentMenuName) {
        this.parentMenuName = parentMenuName;
    }

    public boolean isView() {
        return isView;
    }

    public void setView(boolean isView) {
        this.isView = isView;
    }

    /** 当前表是否主子表模板 */
    public boolean isSub() {
        return isSub(this.tplCategory);
    }

    /**
     * 判断模板类别是否为主子表(sub)
     *
     * @param tplCategory 模板类别
     * @return true 表示主子表
     */
    public static boolean isSub(String tplCategory) {
        return tplCategory != null && StringUtils.equals(GenConstants.TPL_SUB, tplCategory);
    }

    /** 当前表是否树表模板 */
    public boolean isTree() {
        return isTree(this.tplCategory);
    }

    /**
     * 判断模板类别是否为树表(tree)
     *
     * @param tplCategory 模板类别
     * @return true 表示树表
     */
    public static boolean isTree(String tplCategory) {
        return tplCategory != null && StringUtils.equals(GenConstants.TPL_TREE, tplCategory);
    }

    /** 当前表是否普通 CRUD 模板 */
    public boolean isCrud() {
        return isCrud(this.tplCategory);
    }

    /**
     * 判断模板类别是否为普通增删改查(crud)
     *
     * @param tplCategory 模板类别
     * @return true 表示 CRUD
     */
    public static boolean isCrud(String tplCategory) {
        return tplCategory != null && StringUtils.equals(GenConstants.TPL_CRUD, tplCategory);
    }

    /** 当前模板下指定字段是否为父类已声明字段（树表额外含 TreeEntity 字段） */
    public boolean isSuperColumn(String javaField) {
        return isSuperColumn(this.tplCategory, javaField);
    }

    /**
     * 按模板类别判断字段是否属于父类基类字段：树表需同时排除 BASE_ENTITY+TREE_ENTITY，
     * 其余仅排除 BASE_ENTITY
     *
     * @param tplCategory 模板类别
     * @param javaField   Java 字段名
     * @return true 表示由基类提供，无需重复生成
     */
    public static boolean isSuperColumn(String tplCategory, String javaField) {
        if (isTree(tplCategory)) {
            // 树表模板：BaseEntity 与 TreeEntity 的字段都已在父类定义，不重复生成
            return StringUtils.equalsAnyIgnoreCase(javaField,
                    ArrayUtils.addAll(GenConstants.TREE_ENTITY, GenConstants.BASE_ENTITY));
        }
        return StringUtils.equalsAnyIgnoreCase(javaField, GenConstants.BASE_ENTITY);
    }
}
