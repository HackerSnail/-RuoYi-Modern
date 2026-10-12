// Package model 内代码生成实体：gen_table / gen_table_column 的全列映射 + 逐字对齐 Java 的派生方法。
//
// 与 SysJob（仅映射 vo 子集）不同：两张 gen_* 表在编辑/导入/同步/生成的各环节都要读写全部列，
// 故此处做全列映射；columns/pkColumn/subTable/treeCode/… 为 Java 侧瞬态字段（非持久化），
// Go 用 orm:"-" 承载、由 service 按需装配。派生方法（isPk/isSuperColumn/readConverterExp…）
// 与 com.ruoyi.generator.domain.{GenTable,GenTableColumn} 的实例方法逐一对齐。
package model

import (
	"strings"

	"github.com/gogf/gf/v2/os/gtime"
)

// GenTable 对齐 gen_table 全列 + 瞬态装配字段（Java GenTable extends BaseEntity）。
type GenTable struct {
	TableId        int64       `json:"tableId"        orm:"table_id"`
	TableName      string      `json:"tableName"      orm:"table_name"`
	TableComment   string      `json:"tableComment"   orm:"table_comment"`
	SubTableName   string      `json:"subTableName"   orm:"sub_table_name"`
	SubTableFkName string      `json:"subTableFkName" orm:"sub_table_fk_name"`
	ClassName      string      `json:"className"      orm:"class_name"`
	TplCategory    string      `json:"tplCategory"    orm:"tpl_category"`
	TplWebType     string      `json:"tplWebType"     orm:"tpl_web_type"`
	PackageName    string      `json:"packageName"    orm:"package_name"`
	ModuleName     string      `json:"moduleName"     orm:"module_name"`
	BusinessName   string      `json:"businessName"   orm:"business_name"`
	FunctionName   string      `json:"functionName"   orm:"function_name"`
	FunctionAuthor string      `json:"functionAuthor" orm:"function_author"`
	FormColNum     *int        `json:"formColNum"     orm:"form_col_num"` // 可空：指针区分「未选」与 0
	GenType        string      `json:"genType"        orm:"gen_type"`
	GenPath        string      `json:"genPath"        orm:"gen_path"`
	Options        string      `json:"options"        orm:"options"`
	CreateBy       string      `json:"createBy"       orm:"create_by"`
	CreateTime     *gtime.Time `json:"createTime"     orm:"create_time"`
	UpdateBy       string      `json:"updateBy"       orm:"update_by"`
	UpdateTime     *gtime.Time `json:"updateTime"     orm:"update_time"`
	Remark         string      `json:"remark"         orm:"remark"`
	// 瞬态（非持久化，orm:"-"）：由 LEFT JOIN / service 装配，供生成上下文与前端回显。
	Columns        []*GenTableColumn `json:"columns"        orm:"-"`
	PkColumn       *GenTableColumn   `json:"pkColumn"       orm:"-"`
	SubTable       *GenTable         `json:"subTable"       orm:"-"`
	TreeCode       string            `json:"treeCode"       orm:"-"`
	TreeParentCode string            `json:"treeParentCode" orm:"-"`
	TreeName       string            `json:"treeName"       orm:"-"`
	ParentMenuId   int64             `json:"parentMenuId"   orm:"-"`
	ParentMenuName string            `json:"parentMenuName" orm:"-"`
	View           bool              `json:"view"           orm:"-"`
	// Params 对应 BaseEntity.params：编辑期前端传入的树配置/上级菜单等，updateGenTable 序列化成 options。
	Params map[string]interface{} `json:"params" orm:"-"`
}

// GenTableColumn 对齐 gen_table_column 全列。isXxx 存 "0"/"1" 字符串（ColumnFlagYes="1"）。
type GenTableColumn struct {
	ColumnId      int64       `json:"columnId"      orm:"column_id"`
	TableId       int64       `json:"tableId"       orm:"table_id"`
	ColumnName    string      `json:"columnName"    orm:"column_name"`
	ColumnComment string      `json:"columnComment" orm:"column_comment"`
	ColumnType    string      `json:"columnType"    orm:"column_type"`
	JavaType      string      `json:"javaType"      orm:"java_type"`
	JavaField     string      `json:"javaField"     orm:"java_field"`
	IsPk          string      `json:"isPk"          orm:"is_pk"`
	IsIncrement   string      `json:"isIncrement"   orm:"is_increment"`
	IsRequired    string      `json:"isRequired"    orm:"is_required"`
	IsInsert      string      `json:"isInsert"      orm:"is_insert"`
	IsEdit        string      `json:"isEdit"        orm:"is_edit"`
	IsList        string      `json:"isList"        orm:"is_list"`
	IsQuery       string      `json:"isQuery"       orm:"is_query"`
	QueryType     string      `json:"queryType"     orm:"query_type"`
	HtmlType      string      `json:"htmlType"      orm:"html_type"`
	DictType      string      `json:"dictType"      orm:"dict_type"`
	Sort          *int        `json:"sort"          orm:"sort"`
	CreateBy      string      `json:"createBy"      orm:"create_by"`
	CreateTime    *gtime.Time `json:"createTime"    orm:"create_time"`
	UpdateBy      string      `json:"updateBy"      orm:"update_by"`
	UpdateTime    *gtime.Time `json:"updateTime"    orm:"update_time"`
	// TableName 仅批量反读 information_schema 时作分组键（Java @JsonIgnore transient，不参与 JSON）。
	TableName string `json:"-" orm:"table_name"`
}

// yes 复刻 GenTableColumn.isYes(flag)：非空且等于 COLUMN_FLAG_YES("1") 才视为「是」。
// Go 空串近似 Java 的 null/""，均不等于 "1" → false，行为一致。
func yes(flag string) bool { return flag == ColumnFlagYes }

// 各 isXxx 布尔派生（对齐 Java isPk()/isIncrement()/…）：字段名与 IsPk 等 string 字段冲突，故加 Flag 后缀。
func (c *GenTableColumn) IsPkFlag() bool        { return yes(c.IsPk) }
func (c *GenTableColumn) IsIncrementFlag() bool { return yes(c.IsIncrement) }
func (c *GenTableColumn) IsRequiredFlag() bool  { return yes(c.IsRequired) }
func (c *GenTableColumn) IsInsertFlag() bool    { return yes(c.IsInsert) }
func (c *GenTableColumn) IsEditFlag() bool      { return yes(c.IsEdit) }
func (c *GenTableColumn) IsListFlag() bool      { return yes(c.IsList) }
func (c *GenTableColumn) IsQueryFlag() bool     { return yes(c.IsQuery) }

// GetCapJavaField 对齐 getCapJavaField()：javaField 非空则首字母大写，否则原样返回。
func (c *GenTableColumn) GetCapJavaField() string {
	if c.JavaField != "" {
		return capitalize(c.JavaField)
	}
	return c.JavaField
}

// IsSuperColumn 对齐无参实例版 isSuperColumn()：当前列 javaField 是否属父类字段。
func (c *GenTableColumn) IsSuperColumn() bool { return IsSuperColumnField(c.JavaField) }

// IsUsableColumn 对齐 isUsableColumn()：虽属父类字段但白名单内（父ID/排序/备注）仍需参与生成。
func (c *GenTableColumn) IsUsableColumn() bool { return IsUsableColumnField(c.JavaField) }

// ReadConverterExp 对齐 readConverterExp()：从列注释「（）」内提取"0=否 1=是"式映射，转"0=否,1=是"。
func (c *GenTableColumn) ReadConverterExp() string {
	remarks := substringBetween(c.ColumnComment, "（", "）")
	var sb strings.Builder
	// 对齐 Java isNotEmpty(remarks)（substringBetween 未命中返回 null≈此处空串）；仅判非空、不 trim。
	if remarks != "" {
		for _, value := range strings.Split(remarks, " ") {
			if value != "" {
				// 对齐 Java String.substring 的 UTF-16 语义：中文注释里的 BMP 字符（如「元」）
				// 占 3 字节，按字节切会拆坏；按 rune 切对 BMP 与 Java 一致。
				vr := []rune(value)
				startStr := string(vr[:1])
				endStr := string(vr[1:])
				sb.WriteString(startStr)
				sb.WriteString("=")
				sb.WriteString(endStr)
				sb.WriteString(",")
			}
		}
		if sb.Len() > 0 {
			out := sb.String()
			return out[:len(out)-1] // 删除末位逗号
		}
		return ""
	}
	return c.ColumnComment // 兜底：注释为空/无全角括号补充时返回原串（对齐 defaultString）
}

// IsSuperColumnField 对齐静态版 isSuperColumn(javaField)：命中 TREE_ENTITY+BASE_ENTITY 合并名单（忽略大小写）。
func IsSuperColumnField(javaField string) bool {
	return equalsAnyIgnoreCase(javaField, append(append([]string{}, TreeEntity...), BaseEntity...)...)
}

// IsUsableColumnField 对齐静态版 isUsableColumn(javaField)：白名单 parentId/orderNum/remark。
func IsUsableColumnField(javaField string) bool {
	return equalsAnyIgnoreCase(javaField, "parentId", "orderNum", "remark")
}

// IsSub/IsTree/IsCrud 对齐 GenTable 实例版：按 TplCategory 判模板类别。
func (t *GenTable) IsSub() bool  { return t.TplCategory == TplSub }
func (t *GenTable) IsTree() bool { return t.TplCategory == TplTree }
func (t *GenTable) IsCrud() bool { return t.TplCategory == TplCrud }

// IsSuperColumn 对齐 GenTable.isSuperColumn(javaField)：树表额外含 TreeEntity，其余仅 BaseEntity。
func (t *GenTable) IsSuperColumn(javaField string) bool {
	if t.IsTree() {
		return equalsAnyIgnoreCase(javaField, append(append([]string{}, TreeEntity...), BaseEntity...)...)
	}
	return equalsAnyIgnoreCase(javaField, BaseEntity...)
}

// equalsAnyIgnoreCase 复刻 StringUtils.equalsAnyIgnoreCase：忽略大小写命中任一即真。
func equalsAnyIgnoreCase(s string, targets ...string) bool {
	ls := strings.ToLower(s)
	for _, t := range targets {
		if ls == strings.ToLower(t) {
			return true
		}
	}
	return false
}

// capitalize 复刻 StringUtils.capitalize：首字母大写（其余不动）。
func capitalize(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	return strings.ToUpper(string(r[0])) + string(r[1:])
}

// substringBetween 复刻 StringUtils.substringBetween(str, open, close)：取两个分隔符之间首段；未命中返回空串。
func substringBetween(str, open, close string) string {
	i := strings.Index(str, open)
	if i < 0 {
		return ""
	}
	rest := str[i+len(open):]
	j := strings.Index(rest, close)
	if j < 0 {
		return ""
	}
	return rest[:j]
}
