// Package service —— 代码生成工具，逐字对齐 com.ruoyi.generator.util.GenUtils。
//
// Java 侧 initTable/convertClassName 用注入的 GenConfig（读 gen.*）；本仓经 getGenConfig(ctx) 就地取。
// 字符串原语（toCamelCase/convertToCamelCase/endsWithIgnoreCase/substringBefore/substring 及 commons-lang3
// split 语义）均按 Java StringUtils 精确复刻，保证类名/字段名/类型推导与 Java 逐字一致。
package service

import (
	"context"
	"regexp"
	"strconv"
	"strings"

	"ruoyi-goframe/internal/model"
)

// genSep 复刻 StringUtils.SEPARATOR（下划线），供 toCamelCase 使用。
const genSep = '_'

// InitTable 对齐 GenUtils.initTable：按表名与 gen.* 配置推导类名/包名/模块/业务名/功能名/作者/创建人。
func InitTable(ctx context.Context, t *model.GenTable, operName string) {
	cfg := getGenConfig(ctx)
	t.ClassName = ConvertClassName(ctx, t.TableName)
	t.PackageName = cfg.PackageName
	t.ModuleName = GetModuleName(cfg.PackageName)
	t.BusinessName = GetBusinessName(t.TableName)
	t.FunctionName = ReplaceText(t.TableComment)
	t.FunctionAuthor = cfg.Author
	t.CreateBy = operName
}

// ConvertClassName 对齐实例方法 convertClassName：autoRemovePre 且 tablePrefix 非空时先按前缀列表剔除首个命中前缀，
// 再 convertToCamelCase 转大驼峰类名。
func ConvertClassName(ctx context.Context, tableName string) string {
	cfg := getGenConfig(ctx)
	if cfg.AutoRemovePre && cfg.TablePrefix != "" {
		searchList := langSplit(cfg.TablePrefix, ",")
		tableName = replaceFirst(tableName, searchList)
	}
	return convertToCamelCase(tableName)
}

// replaceFirst 对齐 static replaceFirst：逐个前缀比对，命中第一个（字面 startsWith）即按长度剔除，不用正则。
func replaceFirst(replacement string, searchList []string) string {
	text := replacement
	for _, searchString := range searchList {
		if strings.HasPrefix(replacement, searchString) {
			text = replacement[len(searchString):]
			break
		}
	}
	return text
}

// InitColumnField 对齐 static initColumnField：按列类型推导 javaType/htmlType，按列名后缀推导 queryType/控件，
// isInsert 恒 "1"，isEdit/isList/isQuery 依 COLUMNNAME_NOT_* + isPk 决定。分支顺序逐字对齐 Java。
func InitColumnField(col *model.GenTableColumn, t *model.GenTable) {
	dataType := GetDbType(col.ColumnType)
	columnName := col.ColumnName
	col.TableId = t.TableId
	col.CreateBy = t.CreateBy
	col.JavaField = toCamelCase(columnName)
	col.JavaType = model.TypeString
	col.QueryType = model.QueryEq

	if arraysContains(model.ColumnTypeStr, dataType) || arraysContains(model.ColumnTypeText, dataType) {
		columnLength := GetColumnLength(col.ColumnType)
		htmlType := model.HtmlInput
		if columnLength >= 500 || arraysContains(model.ColumnTypeText, dataType) {
			htmlType = model.HtmlTextarea
		}
		col.HtmlType = htmlType
	} else if arraysContains(model.ColumnTypeTime, dataType) {
		col.JavaType = model.TypeDate
		col.HtmlType = model.HtmlDatetime
	} else if arraysContains(model.ColumnTypeNumber, dataType) {
		col.HtmlType = model.HtmlInput
		// 浮点统一 BigDecimal：解析 "(p,s)"/"(len)"；畸形（parseInt 失败）降级 Long（对齐 try/catch）。
		col.JavaType = resolveNumberJavaType(col.ColumnType)
	}

	// 插入字段：默认所有字段都需要插入
	col.IsInsert = model.Require
	// 编辑/列表/查询字段：不在 COLUMNNAME_NOT_* 且非主键才置为需要
	if !arraysContains(model.ColumnNameNotEdit, columnName) && !col.IsPkFlag() {
		col.IsEdit = model.Require
	}
	if !arraysContains(model.ColumnNameNotList, columnName) && !col.IsPkFlag() {
		col.IsList = model.Require
	}
	if !arraysContains(model.ColumnNameNotQuery, columnName) && !col.IsPkFlag() {
		col.IsQuery = model.Require
	}

	// 查询方式：*name 结尾用 LIKE
	if endsWithIgnoreCase(columnName, "name") {
		col.QueryType = model.QueryLike
	}
	// 控件按列名后缀推导（顺序不可乱，else-if 语义）
	if endsWithIgnoreCase(columnName, "status") {
		col.HtmlType = model.HtmlRadio
	} else if endsWithIgnoreCase(columnName, "type") || endsWithIgnoreCase(columnName, "sex") {
		col.HtmlType = model.HtmlSelect
	} else if endsWithIgnoreCase(columnName, "image") {
		col.HtmlType = model.HtmlImageUpload
	} else if endsWithIgnoreCase(columnName, "file") {
		col.HtmlType = model.HtmlFileUpload
	} else if endsWithIgnoreCase(columnName, "content") {
		col.HtmlType = model.HtmlEditor
	}
}

// resolveNumberJavaType 复刻 initColumnField 数字分支：substringBetween("(",")") → commons-lang split(",")。
// nil 语义严格：括号缺失（substringBetween 未命中）视作 null，两元素且刻度>0→BigDecimal，单元素且长度<=10→Integer，
// 其余→Long；任一 parseInt 失败亦降级 Long。
func resolveNumberJavaType(columnType string) string {
	between := substringBetweenOrNil(columnType, "(", ")")
	var parts []string
	if between != nil {
		parts = langSplit(*between, ",")
	}
	// 对齐 Java：str != null && length==2 && parseInt(str[1])>0（len(nil)==0，故 len 判定已隐含非 nil）
	if len(parts) == 2 {
		if n, err := strconv.Atoi(parts[1]); err == nil {
			if n > 0 {
				return model.TypeBigDecimal
			}
		} else {
			return model.TypeLong // NumberFormatException → catch → Long
		}
	}
	if len(parts) == 1 {
		if n, err := strconv.Atoi(parts[0]); err == nil {
			if n <= 10 {
				return model.TypeInteger
			}
		} else {
			return model.TypeLong
		}
	}
	return model.TypeLong
}

// GetDbType 对齐 static getDbType：indexOf("(")>0 时取 "(" 之前，否则原样。
func GetDbType(columnType string) string {
	if strings.Index(columnType, "(") > 0 {
		return substringBefore(columnType, "(")
	}
	return columnType
}

// GetColumnLength 对齐 static getColumnLength：indexOf("(")>0 时取括号内数字（畸形→0），否则 0。
func GetColumnLength(columnType string) int {
	if strings.Index(columnType, "(") > 0 {
		length := substringBetweenOrNil(columnType, "(", ")")
		if length == nil {
			return 0
		}
		if n, err := strconv.Atoi(*length); err == nil {
			return n
		}
		return 0
	}
	return 0
}

// ArraysContains 对齐 static arraysContains：数组是否包含目标值。
func arraysContains(arr []string, target string) bool {
	for _, v := range arr {
		if v == target {
			return true
		}
	}
	return false
}

// GetModuleName 对齐 static getModuleName：取包名最后一段（最后一个 "." 之后）；无 "." 返回整串。
func GetModuleName(packageName string) string {
	lastIndex := strings.LastIndex(packageName, ".")
	return substrJava(packageName, lastIndex+1, len(packageName))
}

// GetBusinessName 对齐 static getBusinessName：取表名最后一段（最后一个 "_" 之后）；无 "_" 返回整串。
func GetBusinessName(tableName string) string {
	lastIndex := strings.LastIndex(tableName, "_")
	return substrJava(tableName, lastIndex+1, len(tableName))
}

// ReplaceText 对齐 static replaceText：RegExUtils.replaceAll(text, "(?:表|若依)", "")。
var genNoiseRe = regexp.MustCompile("(?:表|若依)")

func ReplaceText(text string) string {
	return genNoiseRe.ReplaceAllString(text, "")
}

// ---- 字符串原语（复刻 Java StringUtils / commons-lang3）----

// toCamelCase 复刻 StringUtils.toCamelCase：无 "_" 原样返回；否则整体小写后按下划线转小驼峰。
func toCamelCase(s string) string {
	if strings.IndexByte(s, genSep) == -1 {
		return s
	}
	s = strings.ToLower(s)
	var sb strings.Builder
	upperCase := false
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == byte(genSep) {
			upperCase = true
		} else if upperCase {
			sb.WriteByte(asciiUpper(c))
			upperCase = false
		} else {
			sb.WriteByte(c)
		}
	}
	return sb.String()
}

// convertToCamelCase 复刻 StringUtils.convertToCamelCase：空→""；无 "_"→首字母大写+其余原样；否则按下划线分段，
// 每段首字母大写、其余小写，跳过空段。
func convertToCamelCase(name string) string {
	if name == "" {
		return ""
	}
	if !strings.Contains(name, "_") {
		return asciiUpperStr(name[:1]) + name[1:]
	}
	var result strings.Builder
	for _, camel := range strings.Split(name, "_") {
		if camel == "" {
			continue
		}
		result.WriteString(asciiUpperStr(camel[:1]))
		result.WriteString(strings.ToLower(camel[1:]))
	}
	return result.String()
}

// endsWithIgnoreCase 复刻 StringUtils.endsWithIgnoreCase：忽略大小写判后缀。
func endsWithIgnoreCase(str, suffix string) bool {
	return strings.HasSuffix(strings.ToLower(str), strings.ToLower(suffix))
}

// substringBefore 复刻 StringUtils.substringBefore：首个 sep 之前；无 sep 返回整串。
func substringBefore(str, sep string) string {
	i := strings.Index(str, sep)
	if i < 0 {
		return str
	}
	return str[:i]
}

// substringBetweenOrNil 复刻 StringUtils.substringBetween 的 null 语义：未命中返回 nil（区别于空串）。
func substringBetweenOrNil(str, open, close string) *string {
	i := strings.Index(str, open)
	if i < 0 {
		return nil
	}
	i += len(open)
	j := strings.Index(str[i:], close)
	if j < 0 {
		return nil
	}
	s := str[i : i+j]
	return &s
}

// langSplit 复刻 commons-lang3 StringUtils.split(str, separatorChars)：separatorChars 为「字符集」；
// null/空输入返回空切片；相邻/首尾分隔符视作一个、不产生空 token。
// 注意：入参为具体 string（非指针）；调用方对「null 输入」用 substringBetweenOrNil 先行区分。
func langSplit(str, sepChars string) []string {
	out := make([]string, 0, 4)
	if str == "" {
		return out
	}
	cur := strings.Builder{}
	for _, c := range str {
		if strings.ContainsRune(sepChars, c) {
			if cur.Len() > 0 {
				out = append(out, cur.String())
				cur.Reset()
			}
			continue
		}
		cur.WriteRune(c)
	}
	if cur.Len() > 0 {
		out = append(out, cur.String())
	}
	return out
}

// substrJava 复刻 StringUtils.substring(str, start, end) 的越界安全语义（此处仅用于 start/end 组合取末段）。
func substrJava(str string, start, end int) string {
	if str == "" {
		return ""
	}
	if start < 0 {
		start = 0
	}
	if end < 0 || end > len(str) {
		end = len(str)
	}
	if start >= len(str) || start >= end {
		return ""
	}
	return str[start:end]
}

func asciiUpper(c byte) byte {
	if c >= 'a' && c <= 'z' {
		return c - ('a' - 'A')
	}
	return c
}

func asciiUpperStr(s string) string {
	if s == "" {
		return s
	}
	b := []byte(s)
	b[0] = asciiUpper(b[0])
	return string(b)
}
