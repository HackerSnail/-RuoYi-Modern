// Package service —— 分页基建，复刻 PageHelper.startPage + BaseController.getDataTable 的真实语义。
//
// 关键契约（照 Java 逐条对齐，不得语义错位）：
//   - pageNum 缺省=1、pageSize 缺省=10（TableSupport.getPageDomain 的 Convert.toInt 默认值）。
//   - reasonable 缺省=true（PageDomain.getReasonable 在 null 时返回 TRUE）：页码越界规整到首/末页。
//   - orderByColumn 经 toUnderScoreCase 转下划线并追加 isAsc（默认 asc，兼容 ascending/descending）；
//     列名须通过 escapeOrderBySql 白名单（仅字母数字下划线），否则丢弃以防注入。
//   - total 用 `select count(0) from ( <原始查询(含 distinct/JOIN，无 order) > ) _cnt`，
//     与 PageHelper 无法优化时的子查询计数等价（列表 distinct 去重后计数）。
package service

import (
	"context"
	"math"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"
)

// Page 请求级分页参数（从 query 解析）。
type Page struct {
	PageNum    int
	PageSize   int
	Order      string // 已转义、已转下划线的 ORDER BY 片段（不含前导 "order by"）；空表示不覆盖
	Reasonable bool
}

// ParsePage 从请求 query 解析分页参数，对齐 TableSupport.buildPageRequest 默认值。
func ParsePage(r *ghttp.Request) Page {
	pn := r.Get("pageNum").Int()
	if pn <= 0 {
		pn = 1
	}
	ps := r.Get("pageSize").Int()
	if ps <= 0 {
		ps = 10
	}
	reasonable := true
	if rv := r.Get("reasonable"); !rv.IsNil() && rv.String() != "" {
		reasonable = rv.Bool()
	}
	return Page{
		PageNum:    pn,
		PageSize:   ps,
		Order:      buildOrderBy(r.Get("orderByColumn").String(), r.Get("isAsc").String()),
		Reasonable: reasonable,
	}
}

// buildOrderBy 复刻 PageDomain.getOrderBy + SqlUtil.escapeOrderBySql：
// orderByColumn 空→无覆盖；否则转下划线 + 方向（asc/desc，兼容前端 ascending/descending）。
// 列名含非 [A-Za-z0-9_] 字符则整体丢弃（等价 escapeOrderBySql 抛错后不排序，避免注入面）。
func buildOrderBy(column, isAsc string) string {
	if strings.TrimSpace(column) == "" {
		return ""
	}
	col := toUnderScoreCase(column)
	if !isSafeOrderIdent(col) {
		return ""
	}
	dir := "asc"
	switch strings.ToLower(isAsc) {
	case "desc", "descending":
		dir = "desc"
	default:
		dir = "asc"
	}
	return col + " " + dir
}

// toUnderScoreCase 复刻 StringUtils.toUnderScoreCase：驼峰转下划线（user_name←userName）。
func toUnderScoreCase(s string) string {
	var sb strings.Builder
	for i, c := range s {
		if c >= 'A' && c <= 'Z' {
			if i > 0 {
				prev := rune(s[i-1])
				if !(prev >= 'a' && prev <= 'z') || (i+1 < len(s) && rune(s[i+1]) >= 'a' && rune(s[i+1]) <= 'z') {
					sb.WriteByte('_')
				}
			}
			sb.WriteRune(unicodeLower(c))
		} else {
			sb.WriteRune(c)
		}
	}
	return sb.String()
}

func unicodeLower(c rune) rune {
	if c >= 'A' && c <= 'Z' {
		return c + ('a' - 'A')
	}
	return c
}

func isSafeOrderIdent(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_') {
			return false
		}
	}
	return true
}

// PagedResult 承载一次分页查询的行与总数（total 为规整前、按同 WHERE 的全量计数）。
type PagedResult struct {
	Rows  gdb.Result
	Total int64
}

// QueryPaged 运行分页查询：baseSQL 为「原始数据查询（含 distinct/JOIN，无 ORDER/LIMIT）」，
// order 为覆盖排序（空则用 defaultOrder），先 count 总数再按 reasonable 规整页码取当前页。
func QueryPaged(ctx context.Context, p Page, baseSQL string, args []interface{}, defaultOrder string) (PagedResult, error) {
	countSQL := "select count(0) from (" + baseSQL + ") _cnt"
	total := int64(0)
	if v, err := g.DB().GetValue(ctx, countSQL, args...); err == nil && !v.IsNil() {
		total = v.Int64()
	}
	// reasonable 规整页码：越界回退到首/末页
	pageNum := p.PageNum
	if p.Reasonable {
		if pageNum < 1 {
			pageNum = 1
		}
		pages := int64(1)
		if p.PageSize > 0 {
			pages = int64(math.Ceil(float64(total) / float64(p.PageSize)))
		}
		if pages < 1 {
			pages = 1
		}
		if int64(pageNum) > pages {
			pageNum = int(pages)
		}
	}
	offset := (pageNum - 1) * p.PageSize
	order := p.Order
	if order == "" {
		order = defaultOrder
	}
	dataSQL := baseSQL
	if order != "" {
		dataSQL += " order by " + order
	}
	dataSQL += " limit ?, ?"
	dataArgs := append(append([]interface{}{}, args...), offset, p.PageSize)
	recs, err := g.DB().GetAll(ctx, dataSQL, dataArgs...)
	if err != nil {
		return PagedResult{}, err
	}
	return PagedResult{Rows: recs, Total: total}, nil
}

// DataTable 组装 TableDataInfo：{total,rows,code:200,msg:"查询成功"}（rows 为已序列化列表）。
func DataTable(total int64, rows interface{}) g.Map {
	if rows == nil {
		rows = []interface{}{}
	}
	return g.Map{"total": total, "rows": rows, "code": 200, "msg": "查询成功"}
}
