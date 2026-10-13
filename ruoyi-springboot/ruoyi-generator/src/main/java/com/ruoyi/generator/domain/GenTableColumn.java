package com.ruoyi.generator.domain;

import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.ArrayUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.utils.StringUtils;

/**
 * 代码生成业务字段表 gen_table_column
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class GenTableColumn extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 编号 */
    private Long columnId;

    /** 归属表编号 */
    private Long tableId;

    /** 列名称 */
    private String columnName;

    /** 列描述 */
    private String columnComment;

    /** 列类型 */
    private String columnType;

    /** JAVA类型 */
    private String javaType;

    /** JAVA字段名 */
    @NotBlank(message = "Java属性不能为空")
    private String javaField;

    /** 是否主键（1是） */
    private String isPk;

    /** 是否自增（1是） */
    private String isIncrement;

    /** 是否必填（1是） */
    private String isRequired;

    /** 是否为插入字段（1是） */
    private String isInsert;

    /** 是否编辑字段（1是） */
    private String isEdit;

    /** 是否列表字段（1是） */
    private String isList;

    /** 是否查询字段（1是） */
    private String isQuery;

    /** 查询方式（EQ等于、NE不等于、GT大于、LT小于、LIKE模糊、BETWEEN范围） */
    private String queryType;

    /**
     * 显示类型（input文本框、textarea文本域、select下拉框、checkbox复选框、radio单选框、datetime日期控件、image图片上传控件、upload文件上传控件、editor富文本控件）
     */
    private String htmlType;

    /** 字典类型 */
    private String dictType;

    /** 排序 */
    private Integer sort;

    /**
     * 所属表名：仅在批量反读 information_schema.columns（导入多表）时用作按表分组列的键，
     * 非 gen_table_column 持久化字段（batchInsert 未列该字段），且不参与 JSON 序列化
     */
    @JsonIgnore
    private transient String tableName;

    public void setColumnId(Long columnId) {
        this.columnId = columnId;
    }

    public Long getColumnId() {
        return columnId;
    }

    public void setTableId(Long tableId) {
        this.tableId = tableId;
    }

    public Long getTableId() {
        return tableId;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnComment(String columnComment) {
        this.columnComment = columnComment;
    }

    public String getColumnComment() {
        return columnComment;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setJavaType(String javaType) {
        this.javaType = javaType;
    }

    public String getJavaType() {
        return javaType;
    }

    public void setJavaField(String javaField) {
        this.javaField = javaField;
    }

    public String getJavaField() {
        return javaField;
    }

    public String getCapJavaField() {
        // javaField 理论上由 @NotBlank 守卫，但 DB 反查的脏列可能为空；空值原样返回，避免 capitalize 产出意外结果
        return StringUtils.isNotEmpty(javaField) ? StringUtils.capitalize(javaField) : javaField;
    }

    /** 列标志是否取"是"(COLUMN_FLAG_YES)：统一 7 组 isXxx 判定，从根上消除复制粘贴错 */
    private static boolean isYes(String flag) {
        return flag != null && StringUtils.equals(GenConstants.COLUMN_FLAG_YES, flag);
    }

    public void setIsPk(String isPk) {
        this.isPk = isPk;
    }

    public String getIsPk() {
        return isPk;
    }

    public boolean isPk() {
        return isYes(this.isPk);
    }

    public boolean isPk(String isPk) {
        return isYes(isPk);
    }

    public String getIsIncrement() {
        return isIncrement;
    }

    public void setIsIncrement(String isIncrement) {
        this.isIncrement = isIncrement;
    }

    public boolean isIncrement() {
        return isYes(this.isIncrement);
    }

    public boolean isIncrement(String isIncrement) {
        return isYes(isIncrement);
    }

    public void setIsRequired(String isRequired) {
        this.isRequired = isRequired;
    }

    public String getIsRequired() {
        return isRequired;
    }

    public boolean isRequired() {
        return isYes(this.isRequired);
    }

    public boolean isRequired(String isRequired) {
        return isYes(isRequired);
    }

    public void setIsInsert(String isInsert) {
        this.isInsert = isInsert;
    }

    public String getIsInsert() {
        return isInsert;
    }

    public boolean isInsert() {
        return isYes(this.isInsert);
    }

    public boolean isInsert(String isInsert) {
        return isYes(isInsert);
    }

    public void setIsEdit(String isEdit) {
        this.isEdit = isEdit;
    }

    public String getIsEdit() {
        return isEdit;
    }

    public boolean isEdit() {
        return isYes(this.isEdit);
    }

    public boolean isEdit(String isEdit) {
        return isYes(isEdit);
    }

    public void setIsList(String isList) {
        this.isList = isList;
    }

    public String getIsList() {
        return isList;
    }

    public boolean isList() {
        return isYes(this.isList);
    }

    public boolean isList(String isList) {
        return isYes(isList);
    }

    public void setIsQuery(String isQuery) {
        this.isQuery = isQuery;
    }

    public String getIsQuery() {
        return isQuery;
    }

    public boolean isQuery() {
        return isYes(this.isQuery);
    }

    public boolean isQuery(String isQuery) {
        return isYes(isQuery);
    }

    public void setQueryType(String queryType) {
        this.queryType = queryType;
    }

    public String getQueryType() {
        return queryType;
    }

    public String getHtmlType() {
        return htmlType;
    }

    public void setHtmlType(String htmlType) {
        this.htmlType = htmlType;
    }

    public void setDictType(String dictType) {
        this.dictType = dictType;
    }

    public String getDictType() {
        return dictType;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getSort() {
        return sort;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    /** 当前列是否为父类(BaseEntity/TreeEntity)自带字段，是则不重复生成 Domain 属性 */
    public boolean isSuperColumn() {
        return isSuperColumn(this.javaField);
    }

    /**
     * 判断指定 Java 字段名是否属于父类已声明的公共字段（创建/更新人时间、备注、树相关）
     *
     * @param javaField Java 字段名
     * @return true 表示由 BaseEntity/TreeEntity 提供，无需生成
     * @apiNote 名单统一引用 GenConstants.BASE_ENTITY/TREE_ENTITY，与 GenTable.isSuperColumn 共用同一真相，
     *          杜绝字面量双份维护导致的不一致（含 TreeEntity.children）。
     */
    public static boolean isSuperColumn(String javaField) {
        return StringUtils.equalsAnyIgnoreCase(javaField,
                ArrayUtils.addAll(GenConstants.TREE_ENTITY, GenConstants.BASE_ENTITY));
    }

    /** 当前列虽属父类字段但生成页面仍需使用（白名单） */
    public boolean isUsableColumn() {
        return isUsableColumn(javaField);
    }

    /**
     * 判断指定字段是否在“可用白名单”：isSuperColumn 名单用于避免生成冗余属性，
     * 但某些属性(父ID/排序/备注)在页面需展示，故在此保留不忽略
     *
     * @param javaField Java 字段名
     * @return true 表示需要保留参与生成
     */
    public static boolean isUsableColumn(String javaField) {
        // isSuperColumn()中的名单用于避免生成多余Domain属性，若某些属性在生成页面时需要用到不能忽略，则放在此处白名单
        return StringUtils.equalsAnyIgnoreCase(javaField, "parentId", "orderNum", "remark");
    }

    /**
     * 解析列注释中的取值表达式：从“（）”内提取形如“0=否 1=是”的映射，转为“0=否,1=是”供模板回显
     *
     * @return 转换后的表达式；若注释为空/无全角括号补充则兜底返回空串
     */
    public String readConverterExp() {
        String remarks = StringUtils.substringBetween(this.columnComment, "（", "）");
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(remarks)) {
            for (String value : remarks.split(" ")) {
                if (StringUtils.isNotEmpty(value)) {
                    String startStr = value.substring(0, 1);
                    String endStr = value.substring(1);
                    sb.append(startStr).append("=").append(endStr).append(",");
                }
            }
            return sb.deleteCharAt(sb.length() - 1).toString();
        } else {
            // 注释为空/无全角括号补充时兜底空串，避免把 null 传到模板字符串拼接处
            return StringUtils.defaultString(this.columnComment);
        }
    }
}
