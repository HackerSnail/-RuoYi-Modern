package velocity

// velocity_utils.go —— 逐字移植 com.ruoyi.generator.util.VelocityUtils 的上下文装配与模板清单/文件名推导。
//
// 设计：Go 无反射 Java bean，故 PrepareContext 产出一个 map[string]interface{}，其值要么是标量
// （string/int/bool），要么是解释器可直接消费的切片（[]interface{}）。对 model.GenTable/GenTableColumn
// 的属性/方法由 value.go/methods.go 显式映射，本文件只负责「按 Java 语义把键值填齐」。
//
// 逐字节关键：importList / dicts 在 Java 侧是 HashSet，模板 foreach / join 依赖其 HashMap 桶序；
// 本文件用 javaHashSetOrder 复刻（String.hashCode → 扰动 h^(h>>>16) → 桶 = 扰动 & (cap-1)，cap=16
// 因元素数 ≤ 阈值 12 不扩容；迭代 = 桶升序、同桶按插入序）。datetime 依赖时钟，生产用当日，
// 测试由调用方覆盖为固定值以对齐 oracle。

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service/genvm"
)

const (
	projectPath        = "main/java"
	mybatisPath        = "main/resources/mapper"
	defaultParentMenu  = "3"
	elementPlus        = "element-plus"
	elementPlusTypeScr = "element-plus-typescript"
)

// PrepareContext 对齐 VelocityUtils.prepareContext：装配渲染上下文（不含 datetime 覆盖）。
func PrepareContext(t *model.GenTable) map[string]interface{} {
	functionName := t.FunctionName
	if functionName == "" {
		functionName = "【请填写功能名称】"
	}
	ctx := map[string]interface{}{
		"tplCategory":      t.TplCategory,
		"tableName":        t.TableName,
		"functionName":     functionName,
		"ClassName":        t.ClassName,
		"className":        uncapitalize(t.ClassName),
		"moduleName":       t.ModuleName,
		"BusinessName":     capitalizeStr(t.BusinessName),
		"businessName":     t.BusinessName,
		"basePackage":      getPackagePrefix(t.PackageName),
		"packageName":      t.PackageName,
		"author":           t.FunctionAuthor,
		"colSpan":          getColSpan(t.FormColNum),
		"datetime":         time.Now().Format("2006-01-02"),
		"pkColumn":         t.PkColumn,
		"importList":       getImportList(t),
		"permissionPrefix": t.ModuleName + ":" + t.BusinessName,
		"columns":          toValueSlice(t.Columns),
		"table":            t,
		"dicts":            getDicts(t),
	}
	paramsObj := parseOptions(t.Options)
	ctx["genView"] = genView(paramsObj)
	ctx["parentMenuId"] = getParentMenuId(paramsObj)
	if t.TplCategory == model.TplTree {
		setTreeContext(ctx, t, paramsObj)
	}
	if t.TplCategory == model.TplSub {
		setSubContext(ctx, t)
	}
	return ctx
}

func setTreeContext(ctx map[string]interface{}, t *model.GenTable, paramsObj map[string]interface{}) {
	ctx["treeCode"] = getTreecode(paramsObj)
	ctx["treeParentCode"] = getTreeParentCode(paramsObj)
	ctx["treeName"] = getTreeName(paramsObj)
	ctx["expandColumn"] = getExpandColumn(t)
	if v, ok := paramsObj[model.TreeParentCode]; ok {
		ctx["tree_parent_code"] = jsonString(v)
	}
	if v, ok := paramsObj[model.TreeName]; ok {
		ctx["tree_name"] = jsonString(v)
	}
}

func setSubContext(ctx map[string]interface{}, t *model.GenTable) {
	sub := t.SubTable
	subClassName := ""
	if sub != nil {
		subClassName = sub.ClassName
	}
	subTableFkClassName := convertToCamelCase(t.SubTableFkName)
	ctx["subTable"] = sub
	ctx["subTableName"] = t.SubTableName
	ctx["subTableFkName"] = t.SubTableFkName
	ctx["subTableFkClassName"] = subTableFkClassName
	ctx["subTableFkclassName"] = uncapitalize(subTableFkClassName)
	ctx["subClassName"] = subClassName
	ctx["subclassName"] = uncapitalize(subClassName)
	var subImport []interface{}
	if sub != nil {
		subImport = getImportList(sub)
	}
	ctx["subImportList"] = subImport
}

// GetTemplateList 对齐 VelocityUtils.getTemplateList：按前端类型/模板类别组装待渲染模板路径。
func GetTemplateList(t *model.GenTable) []string {
	tplWebType := t.TplWebType
	tplCategory := t.TplCategory
	paramsObj := parseOptions(t.Options)
	isView := genView(paramsObj)
	useWebType := "vm/vue"
	apiTemplate := "vm/js/api.js.vm"
	if tplWebType == elementPlus {
		useWebType = "vm/vue/v3"
	} else if tplWebType == elementPlusTypeScr {
		useWebType = "vm/vue/v3ts"
		apiTemplate = "vm/ts/api.ts.vm"
	}
	templates := []string{
		"vm/java/domain.java.vm",
		"vm/java/mapper.java.vm",
		"vm/java/service.java.vm",
		"vm/java/serviceImpl.java.vm",
		"vm/java/controller.java.vm",
		"vm/xml/mapper.xml.vm",
		"vm/sql/sql.vm",
		apiTemplate,
	}
	if tplWebType == elementPlusTypeScr {
		templates = append(templates, "vm/ts/type.ts.vm", "vm/ts/index.ts.vm")
	}
	switch tplCategory {
	case model.TplCrud:
		templates = append(templates, useWebType+"/index.vue.vm")
	case model.TplTree:
		templates = append(templates, useWebType+"/index-tree.vue.vm")
	case model.TplSub:
		templates = append(templates, useWebType+"/index.vue.vm", "vm/java/sub-domain.java.vm")
	}
	if isView {
		templates = append(templates, useWebType+"/view.vue.vm")
	}
	return templates
}

// GetFileName 对齐 VelocityUtils.getFileName：由模板路径推 ZIP 内文件名（sub-domain 先判）。
func GetFileName(template string, t *model.GenTable) string {
	packageName := t.PackageName
	moduleName := t.ModuleName
	className := t.ClassName
	businessName := t.BusinessName
	javaPath := projectPath + "/" + strings.ReplaceAll(packageName, ".", "/")
	mbp := mybatisPath + "/" + moduleName
	vuePath := "vue"
	switch {
	case strings.Contains(template, "sub-domain.java.vm") && t.TplCategory == model.TplSub:
		subCN := ""
		if t.SubTable != nil {
			subCN = t.SubTable.ClassName
		}
		return javaPath + "/domain/" + subCN + ".java"
	case strings.Contains(template, "domain.java.vm"):
		return javaPath + "/domain/" + className + ".java"
	case strings.Contains(template, "mapper.java.vm"):
		return javaPath + "/mapper/" + className + "Mapper.java"
	case strings.Contains(template, "service.java.vm"):
		return javaPath + "/service/I" + className + "Service.java"
	case strings.Contains(template, "serviceImpl.java.vm"):
		return javaPath + "/service/impl/" + className + "ServiceImpl.java"
	case strings.Contains(template, "controller.java.vm"):
		return javaPath + "/controller/" + className + "Controller.java"
	case strings.Contains(template, "mapper.xml.vm"):
		return mbp + "/" + className + "Mapper.xml"
	case strings.Contains(template, "sql.vm"):
		return businessName + "Menu.sql"
	case strings.Contains(template, "api.js.vm"):
		return vuePath + "/api/" + moduleName + "/" + businessName + ".js"
	case strings.Contains(template, "api.ts.vm"):
		return vuePath + "/api/" + moduleName + "/" + businessName + ".ts"
	case strings.Contains(template, "type.ts.vm"):
		return vuePath + "/types/api/" + moduleName + "/" + businessName + ".ts"
	case strings.Contains(template, "index.ts.vm"):
		return vuePath + "/types/api/index-bak.ts"
	case strings.Contains(template, "index.vue.vm"):
		return vuePath + "/views/" + moduleName + "/" + businessName + "/index.vue"
	case strings.Contains(template, "index-tree.vue.vm"):
		return vuePath + "/views/" + moduleName + "/" + businessName + "/index.vue"
	case strings.Contains(template, "view.vue.vm"):
		return vuePath + "/views/" + moduleName + "/" + businessName + "/view.vue"
	}
	return ""
}

// ---- 派生取值 ----

func getPackagePrefix(packageName string) string {
	lastIndex := strings.LastIndex(packageName, ".")
	return apacheSubstring(packageName, 0, lastIndex)
}

func getImportList(t *model.GenTable) []interface{} {
	var ins []string
	if t.SubTable != nil {
		ins = append(ins, "java.util.List")
	}
	for _, c := range t.Columns {
		if !c.IsSuperColumn() && c.JavaType == model.TypeDate {
			ins = append(ins, "java.util.Date", "com.fasterxml.jackson.annotation.JsonFormat")
		} else if !c.IsSuperColumn() && c.JavaType == model.TypeBigDecimal {
			ins = append(ins, "java.math.BigDecimal")
		}
	}
	return javaHashSetOrder(ins)
}

func getDicts(t *model.GenTable) string {
	var ins []string
	ins = append(ins, collectDicts(t.Columns)...)
	if t.SubTable != nil {
		ins = append(ins, collectDicts(t.SubTable.Columns)...)
	}
	ordered := javaHashSetOrder(ins)
	parts := make([]string, len(ordered))
	for i, v := range ordered {
		parts[i] = toDisplay(v)
	}
	return strings.Join(parts, ", ")
}

func collectDicts(columns []*model.GenTableColumn) []string {
	var out []string
	for _, c := range columns {
		if !c.IsSuperColumn() && c.DictType != "" &&
			(c.HtmlType == model.HtmlSelect || c.HtmlType == model.HtmlRadio || c.HtmlType == model.HtmlCheckbox) {
			out = append(out, "'"+c.DictType+"'")
		}
	}
	return out
}

func getColSpan(formColNum *int) string {
	if formColNum == nil {
		return "24"
	}
	switch *formColNum {
	case 2:
		return "12"
	case 3:
		return "8"
	}
	return "24"
}

func getParentMenuId(paramsObj map[string]interface{}) string {
	if v, ok := paramsObj[model.ParentMenuId]; ok {
		s := jsonString(v)
		if s != "" {
			return s
		}
	}
	return defaultParentMenu
}

func genView(paramsObj map[string]interface{}) bool {
	if v, ok := paramsObj[model.GenView]; ok {
		switch b := v.(type) {
		case bool:
			return b
		case string:
			return b == "true"
		}
	}
	return false
}

func getTreecode(paramsObj map[string]interface{}) string {
	if v, ok := paramsObj[model.TreeCode]; ok {
		return toCamelCase(jsonString(v))
	}
	return ""
}

func getTreeParentCode(paramsObj map[string]interface{}) string {
	if v, ok := paramsObj[model.TreeParentCode]; ok {
		return toCamelCase(jsonString(v))
	}
	return ""
}

func getTreeName(paramsObj map[string]interface{}) string {
	if v, ok := paramsObj[model.TreeName]; ok {
		return toCamelCase(jsonString(v))
	}
	return ""
}

func getExpandColumn(t *model.GenTable) int {
	paramsObj := parseOptions(t.Options)
	treeName := jsonString(paramsObj[model.TreeName])
	num := 0
	for _, c := range t.Columns {
		if c.IsListFlag() {
			num++
			if c.ColumnName == treeName {
				break
			}
		}
	}
	return num
}

// ---- options 解析 ----

func parseOptions(options string) map[string]interface{} {
	if strings.TrimSpace(options) == "" {
		return map[string]interface{}{}
	}
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(options), &m); err != nil {
		return map[string]interface{}{}
	}
	if m == nil {
		return map[string]interface{}{}
	}
	return m
}

// jsonString 复刻 JSONObject.getString：字符串原样，数字去尾 .0，布尔 "true"/"false"，null→""。
func jsonString(v interface{}) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	case bool:
		if t {
			return "true"
		}
		return "false"
	case float64:
		if t == float64(int64(t)) {
			return strconv.FormatInt(int64(t), 10)
		}
		return formatJavaFloat(t)
	default:
		return toDisplay(v)
	}
}

// ---- 字符串工具（对齐 commons-lang3 / RuoYi 语义）----

func capitalizeStr(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	return strings.ToUpper(string(r[0])) + string(r[1:])
}

func uncapitalize(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	return strings.ToLower(string(r[0])) + string(r[1:])
}

// apacheSubstring 复刻 StringUtils.substring(str, start, end)：负索引按 len+offset 处理，越界钳制。
func apacheSubstring(s string, start, end int) string {
	r := []rune(s)
	n := len(r)
	if start < 0 {
		start = n + start
		if start < 0 {
			start = 0
		}
	}
	if start > n {
		start = n
	}
	if end < 0 {
		end = n + end
	}
	if end > n {
		end = n
	}
	if end < start {
		return ""
	}
	return string(r[start:end])
}

// toCamelCase 复刻 StringUtils.toCamelCase：下划线命名转小驼峰（首段小写，其后段首字母大写）。
func toCamelCase(name string) string {
	if name == "" {
		return name
	}
	parts := strings.Split(name, "_")
	var sb strings.Builder
	for i, p := range parts {
		if p == "" {
			continue
		}
		if i == 0 {
			sb.WriteString(strings.ToLower(p))
		} else {
			r := []rune(p)
			sb.WriteString(strings.ToUpper(string(r[0])) + strings.ToLower(string(r[1:])))
		}
	}
	return sb.String()
}

// convertToCamelCase 复刻 StringUtils.convertToCamelCase：下划线命名转大驼峰（每段首字母大写）。
func convertToCamelCase(name string) string {
	if name == "" {
		return name
	}
	parts := strings.Split(name, "_")
	var sb strings.Builder
	for _, p := range parts {
		if p == "" {
			continue
		}
		r := []rune(p)
		sb.WriteString(strings.ToUpper(string(r[0])) + string(r[1:]))
	}
	return sb.String()
}

// ---- Java HashSet（HashMap）迭代序复刻 ----

// javaStringHash 复刻 java.lang.String.hashCode()：按 UTF-16 code unit，h = 31*h + c（int32 溢出回绕）。
func javaStringHash(s string) int32 {
	var h int32
	for _, r := range s {
		if r <= 0xFFFF {
			h = 31*h + int32(r)
		} else {
			rr := r - 0x10000
			h = 31*h + int32(0xD800+((rr>>10)&0x3FF))
			h = 31*h + int32(0xDC00+(rr&0x3FF))
		}
	}
	return h
}

// javaHashMapBucket 复刻 HashMap 对某 key 的桶下标：扰动 = h ^ (h>>>16)（无符号右移），index = 扰动 & (cap-1)。
func javaHashMapBucket(key string, cap int) int {
	h := uint32(javaStringHash(key))
	spread := h ^ (h >> 16)
	return int(spread & uint32(cap-1))
}

// javaHashSetOrder 复刻 HashSet 迭代序：去重保首个插入序，cap=16（元素数 ≤ 阈值 12 不扩容），
// 按 (桶升序, 插入序) 稳定排序。
func javaHashSetOrder(items []string) []interface{} {
	seen := map[string]bool{}
	order := make([]string, 0, len(items))
	for _, it := range items {
		if !seen[it] {
			seen[it] = true
			order = append(order, it)
		}
	}
	type ent struct {
		key         string
		bucket, idx int
	}
	ents := make([]ent, len(order))
	for i, k := range order {
		ents[i] = ent{k, javaHashMapBucket(k, 16), i}
	}
	sort.SliceStable(ents, func(a, b int) bool {
		if ents[a].bucket != ents[b].bucket {
			return ents[a].bucket < ents[b].bucket
		}
		return ents[a].idx < ents[b].idx
	})
	out := make([]interface{}, len(ents))
	for i, e := range ents {
		out[i] = e.key
	}
	return out
}

// RenderTemplate 生产渲染入口：按 Java 模板相对路径（如 "vm/java/domain.java.vm"）从 genvm 内嵌 FS
// 读取 .vm 字节，解析并渲染。不归一化行尾：模板源是 CRLF，Velocity 保留原始字节，与 Java 侧读
// target/classes/vm 的同一份模板字节完全一致；16-B② oracle 比对与本入口共用同一渲染路径。
func RenderTemplate(tplPath string, ctx map[string]interface{}) (string, error) {
	b, err := genvm.FS.ReadFile(tplPath)
	if err != nil {
		return "", fmt.Errorf("velocity: 读模板 %s: %w", tplPath, err)
	}
	src := string(b) // 不归一化行尾（保 CRLF）
	elems, err := ParseTemplate(src)
	if err != nil {
		return "", fmt.Errorf("velocity: 解析模板 %s: %w", tplPath, err)
	}
	out, err := Render(elems, ctx)
	if err != nil {
		return "", fmt.Errorf("velocity: 渲染模板 %s: %w", tplPath, err)
	}
	return out, nil
}
