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
	"io"
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
		s = convertByExp(s, col.Converter)
	}
	return escapeFormula(s)
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
