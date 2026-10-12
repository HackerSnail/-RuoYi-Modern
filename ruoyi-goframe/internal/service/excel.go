// Package service —— 通用 Excel 导出引擎，取代 Java ExcelUtil<T>（基于 @Excel 注解 + 反射）。
// Go 无字段注解，改为「列元数据切片」驱动：调用方按模块逐列声明顺序/表头名/单元格类型/取值闭包，
// 本引擎只认这张表，与具体实体解耦，供各模块 export 复用。
//
// 忠实对齐 POI 输出（3 参 exportExcel 路径：title 为空，故无合并标题行）：
//   - sheet 名 = 传入的 sheetName；
//   - row1 表头（灰底白字加粗、居中、细边框）→ row2+ 数据（居中、细边框）；
//   - 单元格类型：数字写数值单元、日期按 Layout 格式化、文本写字符串单元；
//   - readConverterExp 表达式翻译（如 0=男,1=女,2=未知）；
//   - escapeFormula：值以 = - + @ 开头时前置 \t，复刻 ExcelUtil.escapeFormulaCell 的防公式注入。
package service

import (
	"bytes"
	"errors"
	"io"
	"regexp"
	"strconv"
	"strings"

	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"
	"github.com/xuri/excelize/v2"
)

// 单元格类型（对齐 @Excel cellType 的有效子集 + 日期列）。
const (
	ExcelKindString  = 0 // 文本（默认）
	ExcelKindNumeric = 1 // 数字（写数值单元）
	ExcelKindText    = 2 // 强制文本（如手机号，避免被判成数字/科学计数）
	ExcelKindDate    = 3 // 日期，按 Layout 格式化
)

// excelDefaultColWidth 对齐 @Excel width() 默认 16。
const excelDefaultColWidth = 16

// ExcelColumn 一列的导出元数据。Value 从行对象取原始值（行以 any 传入，由列定义自行断言具体类型）。
type ExcelColumn struct {
	Name      string            // 列头显示名
	Kind      int               // ExcelKind*
	Width     float64           // 列宽，<=0 用默认 16
	Layout    string            // Kind==Date 时的 Go 时间布局（如 "2006-01-02 15:04:05"）
	Converter string            // readConverterExp，如 "0=男,1=女,2=未知"；空则不转换
	Suffix    string            // @Excel suffix，如 "毫秒"；仅纯值(非日期/非转换)分支生效，对齐 Java setCellVo
	Value     func(row any) any // 从行对象取原始值
}

// excelFormulaPrefix 复刻 ExcelUtil.FORMULA_STR = { "=", "-", "+", "@" }。
var excelFormulaPrefix = []rune{'=', '-', '+', '@'}

// escapeFormula 复刻 escapeFormulaCell：仅当首字符 ∈ FORMULA_STR 时前置制表符（Java 的 startsWithAny
// 守卫只在串首触发，故与 replaceFirst 的定长替换等价）。
func escapeFormula(s string) string {
	if s == "" {
		return s
	}
	first := []rune(s)[0]
	for _, p := range excelFormulaPrefix {
		if first == p {
			return "\t" + s
		}
	}
	return s
}

// convertByExp 复刻 convertByExp 的单选值版：把 "0=男,1=女,2=未知" 按等值映射为显示文本，未命中回原值。
func convertByExp(value, converter string) string {
	if converter == "" {
		return value
	}
	for _, item := range strings.Split(converter, ",") {
		if kv := strings.SplitN(item, "=", 2); len(kv) == 2 && kv[0] == value {
			return kv[1]
		}
	}
	return value
}

// renderCellText 计算文本/日期型单元格的最终字符串（日期格式化 → 表达式转换 → 公式转义）。
func renderCellText(col ExcelColumn, v any) string {
	if v == nil {
		return ""
	}
	if col.Kind == ExcelKindDate {
		if t, ok := v.(*gtime.Time); ok && t != nil {
			layout := col.Layout
			if layout == "" {
				layout = "2006-01-02"
			}
			return escapeFormula(t.Time.Format(layout))
		}
		return escapeFormula(gconv.String(v))
	}
	s := gconv.String(v)
	if col.Converter != "" {
		return escapeFormula(convertByExp(s, col.Converter))
	}
	out := escapeFormula(s)
	// 对齐 Java setCellVo：仅纯 STRING/TEXT 值分支在转义后追加 suffix（date/converter/dict 分支不加）。
	if col.Suffix != "" {
		out += col.Suffix
	}
	return out
}

// excelThinBorder 四边细实线，对齐 POI 单元格默认边框。
func excelThinBorder() []excelize.Border {
	return []excelize.Border{
		{Type: "left", Color: "000000", Style: 1},
		{Type: "right", Color: "000000", Style: 1},
		{Type: "top", Color: "000000", Style: 1},
		{Type: "bottom", Color: "000000", Style: 1},
	}
}

// WriteExcel 按列元数据把行集合写成 xlsx 并写入 w。sheetName 为工作表名，rows 每行经列的 Value 闭包取值。
func WriteExcel(w io.Writer, sheetName string, cols []ExcelColumn, rows []any) error {
	f := excelize.NewFile()
	defer func() { _ = f.Close() }()

	if err := f.SetSheetName("Sheet1", sheetName); err != nil {
		return err
	}

	headerStyle, err := f.NewStyle(&excelize.Style{
		Fill:      excelize.Fill{Type: "pattern", Color: []string{"808080"}, Pattern: 1},
		Font:      &excelize.Font{Color: "FFFFFF", Bold: true},
		Alignment: &excelize.Alignment{Horizontal: "center", Vertical: "center", WrapText: true},
		Border:    excelThinBorder(),
	})
	if err != nil {
		return err
	}
	dataStyle, err := f.NewStyle(&excelize.Style{
		Alignment: &excelize.Alignment{Horizontal: "center", Vertical: "center"},
		Border:    excelThinBorder(),
	})
	if err != nil {
		return err
	}

	// 表头行（row 1，excelize 为 1-based）。
	for c, col := range cols {
		letter, err := excelize.ColumnNumberToName(c + 1)
		if err != nil {
			return err
		}
		cell := letter + "1"
		if err := f.SetCellValue(sheetName, cell, col.Name); err != nil {
			return err
		}
		if err := f.SetCellStyle(sheetName, cell, cell, headerStyle); err != nil {
			return err
		}
		width := col.Width
		if width <= 0 {
			width = excelDefaultColWidth
		}
		if err := f.SetColWidth(sheetName, letter, letter, width); err != nil {
			return err
		}
	}

	// 数据行（row 2+）。
	for r, row := range rows {
		for c, col := range cols {
			letter, err := excelize.ColumnNumberToName(c + 1)
			if err != nil {
				return err
			}
			cell := letter + strconv.Itoa(r+2)
			v := col.Value(row)
			if col.Kind == ExcelKindNumeric && v != nil {
				if err := f.SetCellValue(sheetName, cell, gconv.Float64(v)); err != nil {
					return err
				}
			} else {
				if err := f.SetCellValue(sheetName, cell, renderCellText(col, v)); err != nil {
					return err
				}
			}
			if err := f.SetCellStyle(sheetName, cell, cell, dataStyle); err != nil {
				return err
			}
		}
	}

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return err
	}
	_, err = w.Write(buf.Bytes())
	return err
}

// numDotZero 复刻 Java 对字符串列值 ^\d+\.0$ 的判定（POI 数值单元格产物，如 "1.0"）。
var numDotZero = regexp.MustCompile(`^\d+\.0$`)

// normalizeNumString 复刻 Java 字符串分支：值形如 "123.0" 时去掉尾部 ".0"（excelize 显示值通常已是 "1"，此为守卫）。
func normalizeNumString(s string) string {
	if numDotZero.MatchString(s) {
		return s[:len(s)-2]
	}
	return s
}

// reverseByExp 复刻 ExcelUtil.reverseByExp：把显示值反向映射回键（如 "男"→"0"）。
// 忠实保留 Java 怪癖：单值无匹配时返回空串（propertyString 未追加，stripEnd("")==""）。
func reverseByExp(propertyValue, converter, separator string) string {
	var out strings.Builder
	for _, item := range strings.Split(converter, ",") { // 表达式固定以逗号分隔（Java SEPARATOR）
		ia := strings.Split(item, "=")
		if len(ia) != 2 {
			continue
		}
		if strings.Contains(propertyValue, separator) {
			for _, v := range strings.Split(propertyValue, separator) {
				if ia[1] == v {
					out.WriteString(ia[0])
					out.WriteString(separator)
					break
				}
			}
		} else if ia[1] == propertyValue {
			return ia[0]
		}
	}
	return strings.TrimRight(out.String(), separator)
}

// ExcelImportColumn 定义从模板/导入表读回的一列：按表头名（非顺序）定位，取值文本经可选反向转换后交给 Set。
type ExcelImportColumn struct {
	Name      string                     // 表头显示名（对齐 @Excel name）
	Converter string                     // readConverterExp：读取时反向（显示值→键）；空则按数值串归一
	Set       func(row any, text string) // 把最终文本写回实体字段
}

// ReadExcel 读回首个 sheet：row0 为表头，row1+ 为数据（逐行经 cols 回填到 newRow()）。忠实 Java importExcel 读取语义。
func ReadExcel(rd io.Reader, cols []ExcelImportColumn, newRow func() any) ([]any, error) {
	f, err := excelize.OpenReader(rd)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	sheets := f.GetSheetList()
	if len(sheets) == 0 {
		return nil, errors.New("文件sheet不存在")
	}
	rows, err := f.GetRows(sheets[0])
	if err != nil {
		return nil, err
	}
	if len(rows) <= 1 {
		return nil, nil // 仅表头或空 → 无数据行（对齐 Java getLastRowNum）
	}
	header := rows[0]
	idx := make(map[string]int, len(header))
	for c, h := range header {
		if key := strings.TrimSpace(h); key != "" {
			idx[key] = c // 同名表头后者覆盖（对齐 Java cellMap.put 的 last-wins）
		}
	}
	out := make([]any, 0, len(rows)-1)
	for _, row := range rows[1:] {
		if importRowBlank(row) {
			continue
		}
		e := newRow()
		for _, col := range cols {
			ci, ok := idx[col.Name]
			if !ok {
				continue
			}
			var text string
			if ci < len(row) {
				text = strings.TrimSpace(row[ci])
			}
			if col.Converter != "" {
				text = reverseByExp(text, col.Converter, ",")
			} else {
				text = normalizeNumString(text)
			}
			if col.Set != nil {
				col.Set(e, text)
			}
		}
		out = append(out, e)
	}
	return out, nil
}

// importRowBlank 复刻 isRowEmpty：整行所有单元格去空白后皆空 → 视为空行跳过。
func importRowBlank(row []string) bool {
	for _, c := range row {
		if strings.TrimSpace(c) != "" {
			return false
		}
	}
	return true
}
