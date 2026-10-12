// Package velocity —— Go 版 Velocity 子集解释器（批次 16-B）。
//
// 设计口径（父 spec 决策）：复用 ruoyi-generator 的 19 个 .vm 模板（原样 embed、模板源不动），
// 在其上实现一个只覆盖「模板实际用到的语法面」的 Velocity 子集解释器；逐字节正确性以 Apache
// Velocity 生成的金标准（oracle）为唯一裁判（见 16-B②），本包先把内核做自洽。
//
// 支持面（由 21 个模板实测枚举锁定，未用到的特性一律不实现）：
//   - 指令：#if / #elseif / #else / #end / #foreach(...) / #set(...) / #break
//   - 引用：$x、${x}、属性-方法链 $a.b.c、方法调用 $x.m(实参)
//   - foreach 变量：$foreach.count(1 基)、$foreach.hasNext
//   - 表达式：算术 + - * / %（+ 任一为串则拼接）、比较 == != < > <= >=、逻辑 && || !、字面量（单/双引号串、整数、true/false）
//   - #{}（MyBatis 参绑）与 #非指令 原样输出；$$ 之外不触发解析
//
// 值模型：上下文为 map[string]interface{}；对 model.GenTable/GenTableColumn 的属性/方法按 Java
// getter 语义显式映射（布尔走 isXxx()、字符串走 getXxx()），不依赖反射，保证与 Java 逐字一致。
package velocity

import (
	"strconv"

	"ruoyi-goframe/internal/model"
)

// ---- 真值判定（Velocity：null→false；Boolean→自身；其余非 null→true）----

func truthy(v interface{}) bool {
	if v == nil {
		return false
	}
	switch t := v.(type) {
	case bool:
		return t
	default:
		return true
	}
}

// ---- 数值/字符串强转辅助（对齐 Java 语义的宽松转换）----

func toNumber(v interface{}) (float64, bool) {
	switch t := v.(type) {
	case int:
		return float64(t), true
	case int64:
		return float64(t), true
	case float64:
		return t, true
	case float32:
		return float64(t), true
	case string:
		f, err := strconv.ParseFloat(t, 64)
		if err == nil {
			return f, true
		}
	}
	return 0, false
}

// toDisplay 把求值结果渲染成 Velocity 输出文本（对齐 Object.toString）：
// nil→字面量（调用方按 $引用 原样输出，见 render 引用分支此处仅处理可显示值）。
func toDisplay(v interface{}) string {
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
	case int:
		return strconv.Itoa(t)
	case int64:
		return strconv.FormatInt(t, 10)
	case float64:
		return formatJavaFloat(t)
	case nilString:
		return string(t)
	default:
		return ""
	}
}

// formatJavaFloat：整数值浮点不带小数点（对齐 Java Double.toString 在 == 比较展示场景；
// 模板内数值多为 int，此分支仅兜底）。
func formatJavaFloat(f float64) string {
	if f == float64(int64(f)) {
		return strconv.FormatInt(int64(f), 10)
	}
	return strconv.FormatFloat(f, 'g', -1, 64)
}

// ---- 属性解析：obj.name（含 Java getter 语义映射）----

// getProperty 复刻 Velocity 属性访问：对 GenTable/GenTableColumn 用显式 getter 语义映射；
// 对 map 按键取值；对 string 支持 length。返回 (值, 是否命中)。
func getProperty(obj interface{}, name string) (interface{}, bool) {
	switch o := obj.(type) {
	case *model.GenTableColumn:
		return columnProperty(o, name)
	case *model.GenTable:
		return tableProperty(o, name)
	case map[string]interface{}:
		v, ok := o[name]
		return v, ok
	case string:
		if name == "length" {
			return len([]rune(o)), true
		}
	case *loopState:
		switch name {
		case "count":
			return o.count, true
		case "index":
			return o.count - 1, true
		case "hasNext":
			return o.count < o.total, true
		case "first":
			return o.count == 1, true
		case "last":
			return o.count == o.total, true
		}
	}
	return nil, false
}

func columnProperty(c *model.GenTableColumn, name string) (interface{}, bool) {
	if c == nil {
		return nil, false
	}
	switch name {
	// 布尔派生（Java isXxx()）
	case "pk":
		return c.IsPkFlag(), true
	case "increment":
		return c.IsIncrementFlag(), true
	case "required":
		return c.IsRequiredFlag(), true
	case "insert":
		return c.IsInsertFlag(), true
	case "edit":
		return c.IsEditFlag(), true
	case "list":
		return c.IsListFlag(), true
	case "query":
		return c.IsQueryFlag(), true
	case "superColumn":
		return c.IsSuperColumn(), true
	case "usableColumn":
		return c.IsUsableColumn(), true
	case "capJavaField":
		return c.GetCapJavaField(), true
	// 字符串字段（Java getXxx()）
	case "columnId":
		return c.ColumnId, true
	case "tableId":
		return c.TableId, true
	case "columnName":
		return c.ColumnName, true
	case "columnComment":
		return c.ColumnComment, true
	case "columnType":
		return c.ColumnType, true
	case "javaType":
		return c.JavaType, true
	case "javaField":
		return c.JavaField, true
	case "queryType":
		return c.QueryType, true
	case "htmlType":
		return c.HtmlType, true
	case "dictType":
		return c.DictType, true
	case "createBy":
		return c.CreateBy, true
	case "updateBy":
		return c.UpdateBy, true
	}
	return nil, false
}

func tableProperty(t *model.GenTable, name string) (interface{}, bool) {
	if t == nil {
		return nil, false
	}
	switch name {
	// 布尔派生（Java isXxx()）
	case "sub":
		return t.IsSub(), true
	case "tree":
		return t.IsTree(), true
	case "crud":
		return t.IsCrud(), true
	// 瞬态/装配字段
	case "columns":
		return toValueSlice(t.Columns), true
	case "pkColumn":
		if t.PkColumn != nil {
			return t.PkColumn, true
		}
		return nil, true
	case "subTable":
		if t.SubTable != nil {
			return t.SubTable, true
		}
		return nil, true
	case "view":
		return t.View, true
	case "treeCode":
		return t.TreeCode, true
	case "treeParentCode":
		return t.TreeParentCode, true
	case "treeName":
		return t.TreeName, true
	case "parentMenuId":
		return t.ParentMenuId, true
	case "parentMenuName":
		return t.ParentMenuName, true
	// 持久化字段
	case "tableId":
		return t.TableId, true
	case "tableName":
		return t.TableName, true
	case "tableComment":
		return t.TableComment, true
	case "subTableName":
		return t.SubTableName, true
	case "subTableFkName":
		return t.SubTableFkName, true
	case "className":
		return t.ClassName, true
	case "tplCategory":
		return t.TplCategory, true
	case "tplWebType":
		return t.TplWebType, true
	case "packageName":
		return t.PackageName, true
	case "moduleName":
		return t.ModuleName, true
	case "businessName":
		return t.BusinessName, true
	case "functionName":
		return t.FunctionName, true
	case "functionAuthor":
		return t.FunctionAuthor, true
	case "formColNum":
		if t.FormColNum != nil {
			return *t.FormColNum, true
		}
		return nil, true
	case "genType":
		return t.GenType, true
	case "genPath":
		return t.GenPath, true
	}
	return nil, false
}

// toValueSlice 把 []*model.GenTableColumn 统一成 []interface{}，便于 foreach/size/get 泛化处理。
func toValueSlice(cols []*model.GenTableColumn) []interface{} {
	out := make([]interface{}, len(cols))
	for i, c := range cols {
		out[i] = c
	}
	return out
}
