package velocity

import (
	"fmt"
	"regexp"
	"sort"
	"strings"

	"ruoyi-goframe/internal/model"
)

// callMethod 复刻模板中出现的 Java 方法调用（按枚举实现，未用到的不做）。
// String：indexOf/substring/toUpperCase/toLowerCase/trim/replace/split/equals/
// equalsIgnoreCase/startsWith/endsWith/charAt/length；List：size/isEmpty/get/contains；
// Map：get/containsKey/keySet/size。
//
// ⚠️ 逐字节边角（单/双引号插值、substring 越界、split 正则、UTF-16 vs rune 索引）以 oracle 为准；
// 此处按 Java 文档语义实现（索引/截断以 rune 为单位，中文 BMP 字符 1 rune = 1 UTF-16 单元，等价）。
func callMethod(obj interface{}, name string, args []interface{}) (interface{}, error) {
	switch o := obj.(type) {
	case string:
		return callString(o, name, args)
	case []interface{}:
		return callList(o, name, args)
	case map[string]interface{}:
		return callMap(o, name, args)
	case *model.GenTable:
		return callGenTable(o, name, args)
	case *model.GenTableColumn:
		return callGenTableColumn(o, name, args)
	case nil:
		return nil, fmt.Errorf("velocity: 对 null 调用方法 %s()", name)
	}
	return nil, fmt.Errorf("velocity: 未支持的方法调用目标 %T.%s()", obj, name)
}

// callGenTable 复刻模板对 GenTable 的方法调用（带参 isSuperColumn(javaField) 等）。
func callGenTable(t *model.GenTable, name string, args []interface{}) (interface{}, error) {
	if t == nil {
		return nil, fmt.Errorf("velocity: 对 null $table 调用 %s()", name)
	}
	switch name {
	case "isSuperColumn":
		if len(args) < 1 {
			return nil, fmt.Errorf("velocity: isSuperColumn 需要 javaField 参数")
		}
		return t.IsSuperColumn(toDisplay(args[0])), nil
	case "isSub":
		return t.IsSub(), nil
	case "isTree":
		return t.IsTree(), nil
	case "isCrud":
		return t.IsCrud(), nil
	}
	return nil, fmt.Errorf("velocity: 未支持的 GenTable 方法 %s()", name)
}

// callGenTableColumn 复刻模板对 GenTableColumn 的方法调用（无参 readConverterExp() 等）。
func callGenTableColumn(c *model.GenTableColumn, name string, args []interface{}) (interface{}, error) {
	if c == nil {
		return nil, fmt.Errorf("velocity: 对 null $column 调用 %s()", name)
	}
	switch name {
	case "readConverterExp":
		return c.ReadConverterExp(), nil
	case "isSuperColumn":
		if len(args) >= 1 {
			return model.IsSuperColumnField(toDisplay(args[0])), nil
		}
		return c.IsSuperColumn(), nil
	case "isUsableColumn":
		if len(args) >= 1 {
			return model.IsUsableColumnField(toDisplay(args[0])), nil
		}
		return c.IsUsableColumn(), nil
	case "getCapJavaField":
		return c.GetCapJavaField(), nil
	}
	return nil, fmt.Errorf("velocity: 未支持的 GenTableColumn 方法 %s()", name)
}

func callString(s, name string, args []interface{}) (interface{}, error) {
	runes := []rune(s)
	switch name {
	case "indexOf":
		if len(args) < 1 {
			return nil, fmt.Errorf("velocity: indexOf 需要参数")
		}
		sub := toDisplay(args[0])
		from := 0
		if len(args) >= 2 {
			from, _ = toInt(args[1])
		}
		return javaIndexOf(runes, []rune(sub), from), nil
	case "substring":
		begin, _ := toInt(args[0])
		if begin < 0 {
			begin = 0
		}
		if begin > len(runes) {
			begin = len(runes)
		}
		end := len(runes)
		if len(args) >= 2 {
			end, _ = toInt(args[1])
			if end < begin {
				end = begin
			}
			if end > len(runes) {
				end = len(runes)
			}
		}
		return string(runes[begin:end]), nil
	case "toUpperCase":
		return strings.ToUpper(s), nil
	case "toLowerCase":
		return strings.ToLower(s), nil
	case "trim":
		return javaTrim(s), nil
	case "replace":
		if len(args) < 2 {
			return nil, fmt.Errorf("velocity: replace 需要两参数")
		}
		return strings.ReplaceAll(s, toDisplay(args[0]), toDisplay(args[1])), nil
	case "split":
		sep := ","
		if len(args) >= 1 {
			sep = toDisplay(args[0])
		}
		return javaSplit(s, sep), nil
	case "equals":
		return len(args) >= 1 && s == toDisplay(args[0]), nil
	case "equalsIgnoreCase":
		return len(args) >= 1 && strings.EqualFold(s, toDisplay(args[0])), nil
	case "startsWith":
		return len(args) >= 1 && strings.HasPrefix(s, toDisplay(args[0])), nil
	case "endsWith":
		return len(args) >= 1 && strings.HasSuffix(s, toDisplay(args[0])), nil
	case "charAt":
		i, _ := toInt(args[0])
		if i < 0 || i >= len(runes) {
			return nil, fmt.Errorf("velocity: charAt 越界 %d", i)
		}
		return string(runes[i]), nil
	case "length":
		return len(runes), nil
	case "matches":
		// Java String.matches：整个串须匹配正则。Go 用 ^(?:...)$ 包裹等价。
		if len(args) < 1 {
			return nil, fmt.Errorf("velocity: matches 需要正则参数")
		}
		pat := "^(?:" + toDisplay(args[0]) + ")$"
		re, err := regexp.Compile(pat)
		if err != nil {
			return nil, fmt.Errorf("velocity: matches 正则非法 %q: %v", pat, err)
		}
		return re.MatchString(s), nil
	}
	return nil, fmt.Errorf("velocity: 未支持的 String 方法 %s()", name)
}

// javaIndexOf 复刻 String.indexOf(String, from)：忽略大小写? 否，大小写敏感；未命中 -1；按 rune 计。
func javaIndexOf(text, sub []rune, from int) int {
	if from < 0 {
		from = 0
	}
	if len(sub) == 0 {
		if from > len(text) {
			return len(text)
		}
		return from
	}
	for i := from; i+len(sub) <= len(text); i++ {
		match := true
		for k := 0; k < len(sub); k++ {
			if text[i+k] != sub[k] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

// javaTrim 复刻 String.trim()：删除首尾 codepoint <= U+0020 的字符。
func javaTrim(s string) string {
	r := []rune(s)
	start := 0
	for start < len(r) && r[start] <= 0x20 {
		start++
	}
	end := len(r)
	for end > start && r[end-1] <= 0x20 {
		end--
	}
	return string(r[start:end])
}

// javaSplit 复刻 String.split(regex)：按正则拆，并去掉尾部空串（Java limit=0 语义）。
func javaSplit(s, sep string) []interface{} {
	re, err := regexp.Compile(sep)
	var parts []string
	if err != nil {
		parts = strings.Split(s, sep)
	} else {
		parts = re.Split(s, -1)
	}
	for len(parts) > 0 && parts[len(parts)-1] == "" {
		parts = parts[:len(parts)-1]
	}
	out := make([]interface{}, len(parts))
	for i, p := range parts {
		out[i] = p
	}
	return out
}

func callList(list []interface{}, name string, args []interface{}) (interface{}, error) {
	switch name {
	case "size":
		return len(list), nil
	case "isEmpty":
		return len(list) == 0, nil
	case "get":
		i, _ := toInt(args[0])
		if i < 0 || i >= len(list) {
			return nil, fmt.Errorf("velocity: List.get 越界 %d/%d", i, len(list))
		}
		return list[i], nil
	case "contains":
		target := args[0]
		for _, e := range list {
			if valuesEqual(e, target) {
				return true, nil
			}
		}
		return false, nil
	}
	return nil, fmt.Errorf("velocity: 未支持的 List 方法 %s()", name)
}

func callMap(m map[string]interface{}, name string, args []interface{}) (interface{}, error) {
	switch name {
	case "get":
		k := toDisplay(args[0])
		return m[k], nil
	case "containsKey":
		_, ok := m[toDisplay(args[0])]
		return ok, nil
	case "size":
		return len(m), nil
	case "keySet":
		keys := make([]string, 0, len(m))
		for k := range m {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		out := make([]interface{}, len(keys))
		for i, k := range keys {
			out[i] = k
		}
		return out, nil
	}
	return nil, fmt.Errorf("velocity: 未支持的 Map 方法 %s()", name)
}
