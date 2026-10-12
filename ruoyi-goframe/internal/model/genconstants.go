// Package model 内代码生成常量：逐字移植 com.ruoyi.common.constant.GenConstants。
//
// 落 model 包（而非 spec 初稿设想的 service 包）的原因：GenTable/GenTableColumn 的派生方法
// （isPk/isSuperColumn/isUsableColumn/readConverterExp 等）必须在 model 包，而这些方法依赖
// BASE_ENTITY/TREE_ENTITY/COLUMN_FLAG_YES/TPL_* 等常量；Go 导入方向为 service→model，
// 常量若放 service 会形成 model→service 的反向依赖。故常量下沉 model 作单一真相源，
// service（GenUtils 等）经 model.Xxx 引用，值与 Java 完全一致。
package model

// 模板类别（TPL_*）。
const (
	TplCrud = "crud" // 单表（增删改查）
	TplTree = "tree" // 树表（增删改查）
	TplSub  = "sub"  // 主子表（增删改查）
)

// 生成选项键（存于 gen_table.options 的 JSON 内，对齐 VelocityUtils/validateEdit 取值键）。
const (
	TreeCode        = "treeCode"       // 树编码字段
	TreeParentCode  = "treeParentCode" // 树父编码字段
	TreeName        = "treeName"       // 树名称字段
	ParentMenuId    = "parentMenuId"   // 上级菜单ID字段
	ParentMenuName  = "parentMenuName" // 上级菜单名称字段
	GenView         = "genView"        // 生成详情页开关
)

// 数据库列类型分组（COLUMNTYPE_*），值/顺序逐字对齐 Java 数组。
var (
	ColumnTypeStr    = []string{"char", "varchar", "nvarchar", "varchar2"}
	ColumnTypeText   = []string{"tinytext", "text", "mediumtext", "longtext"}
	ColumnTypeTime   = []string{"datetime", "time", "date", "timestamp"}
	ColumnTypeNumber = []string{"tinyint", "smallint", "mediumint", "int", "number", "integer",
		"bit", "bigint", "float", "double", "decimal"}
)

// 页面不需要的字段名（COLUMNNAME_NOT_*），值/顺序逐字对齐 Java。
var (
	ColumnNameNotEdit  = []string{"id", "create_by", "create_time", "del_flag"}
	ColumnNameNotList  = []string{"id", "create_by", "create_time", "del_flag", "update_by", "update_time"}
	ColumnNameNotQuery = []string{"id", "create_by", "create_time", "del_flag", "update_by", "update_time", "remark"}
)

// 基类字段名单（BASE_ENTITY/TREE_ENTITY），isSuperColumn 据此排除重复生成。
var (
	BaseEntity = []string{"createBy", "createTime", "updateBy", "updateTime", "remark"}
	TreeEntity = []string{"parentName", "parentId", "orderNum", "ancestors", "children"}
)

// HTML 显示控件类型（HTML_*）。
const (
	HtmlInput       = "input"
	HtmlTextarea    = "textarea"
	HtmlSelect      = "select"
	HtmlRadio       = "radio"
	HtmlCheckbox    = "checkbox"
	HtmlDatetime    = "datetime"
	HtmlImageUpload = "imageUpload"
	HtmlFileUpload  = "fileUpload"
	HtmlEditor      = "editor"
)

// Java 类型名（TYPE_*），供 GenUtils 推导 javaType。
const (
	TypeString    = "String"
	TypeInteger   = "Integer"
	TypeLong      = "Long"
	TypeDouble    = "Double"
	TypeBigDecimal = "BigDecimal"
	TypeDate      = "Date"
)

// 查询方式（QUERY_*）。
const (
	QueryLike = "LIKE"
	QueryEq   = "EQ"
)

// REQUIRE/COLUMN_FLAG_YES：gen_table_column 各 is_xxx「是」标志取值（Java 皆 "1"）。
const (
	Require       = "1"
	ColumnFlagYes = "1"
)
