// Package service —— 岗位 /system/post，复刻 SysPostServiceImpl + SysPostMapper.xml 全量语义：
// 列表条件过滤/分页、按主键查询、名称/编码唯一性校验、插入/更新（动态非空列）、批量删除（已分配护栏 + 硬删，sys_post 无软删）；
// 亦供 /system/user getInfo 组装 posts / postIds（SelectPostAll / SelectPostListByUserId）。
package service

import (
	"context"
	"fmt"
	"io"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectPostVo 对应 SysPostMapper.xml <sql id="selectPostVo">（不含 update_by/update_time，同 resultMap 零值）。
const selectPostVo = "select post_id, post_code, post_name, post_sort, status, create_by, create_time, remark from sys_post"

// SelectPostAll 对应 selectPostAll：全量岗位（用户表单岗位下拉）。
func SelectPostAll(ctx context.Context) []model.SysPost {
	recs, err := g.DB().GetAll(ctx, selectPostVo)
	if err != nil {
		return []model.SysPost{}
	}
	return scanPosts(recs)
}

// SelectPostListByUserId 对应 selectPostListByUserId：取该用户已关联岗位 ID 列表（编辑回显勾选）。
func SelectPostListByUserId(ctx context.Context, userId int64) []int64 {
	recs, err := g.DB().GetAll(ctx,
		"select p.post_id from sys_post p"+
			" left join sys_user_post up on up.post_id = p.post_id"+
			" left join sys_user u on u.user_id = up.user_id"+
			" where u.user_id = ?", userId)
	if err != nil {
		return []int64{}
	}
	out := make([]int64, 0, len(recs))
	for _, rec := range recs {
		id := gconv.Int64(rec.Map()["post_id"])
		if id != 0 {
			out = append(out, id)
		}
	}
	return out
}

func scanPosts(recs gdb.Result) []model.SysPost {
	out := make([]model.SysPost, 0, len(recs))
	for _, r := range recs {
		var p model.SysPost
		if gconv.Struct(r.Map(), &p) == nil {
			out = append(out, p)
		}
	}
	return out
}

// PostListFilter 岗位列表条件（对应 SysPost 绑定字段：编码模糊/状态精确/名称模糊）。
type PostListFilter struct {
	PostCode string
	Status   string
	PostName string
}

// buildPostListSQL 组装列表查询（sys_post 无 del_flag，硬删；<where> 逐条 AND 拼接）。
func buildPostListSQL(f PostListFilter) (string, []interface{}) {
	sql := selectPostVo
	conds := make([]string, 0, 3)
	args := make([]interface{}, 0, 3)
	if f.PostCode != "" {
		conds = append(conds, "post_code like concat('%', ?, '%')")
		args = append(args, f.PostCode)
	}
	if f.Status != "" {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if f.PostName != "" {
		conds = append(conds, "post_name like concat('%', ?, '%')")
		args = append(args, f.PostName)
	}
	if len(conds) > 0 {
		sql += " where " + joinStrings(conds, " AND ")
	}
	return sql, args
}

// SelectPostList 对应 selectPostList（不分页，供存在性判定）。
func SelectPostList(ctx context.Context, f PostListFilter) []model.SysPost {
	sql, args := buildPostListSQL(f)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanPosts(recs)
}

// SelectPostListPaged 对应 list 端点 startPage + selectPostList（无默认排序，对齐 Java）。
func SelectPostListPaged(ctx context.Context, f PostListFilter, p Page) ([]model.SysPost, int64, error) {
	sql, args := buildPostListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanPosts(res.Rows), res.Total, nil
}

// SelectPostListLimited 对应 exportLimit 包裹的 selectPostList（无 DataScope、无默认排序，与 Java 一致）：
// 同列表 SQL 追加 limit maxRows+1。
func SelectPostListLimited(ctx context.Context, f PostListFilter, maxRows int) ([]model.SysPost, error) {
	sql, args := buildPostListSQL(f)
	sql += " limit ?"
	args = append(args, maxRows+1)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanPosts(recs), nil
}

// colPost 把「从 *model.SysPost 取值」的闭包适配成引擎的 func(any) any。
func colPost(fn func(*model.SysPost) any) func(any) any {
	return func(row any) any {
		p, ok := row.(*model.SysPost)
		if !ok || p == nil {
			return nil
		}
		return fn(p)
	}
}

// postExportColumns 岗位导出列：按 SysPost 的 @Excel 声明序（仅 postId NUMERIC，status 走 readConverterExp）。
func postExportColumns() []ExcelColumn {
	return []ExcelColumn{
		{Name: "岗位序号", Kind: ExcelKindNumeric, Value: colPost(func(p *model.SysPost) any { return p.PostId })},
		{Name: "岗位编码", Value: colPost(func(p *model.SysPost) any { return p.PostCode })},
		{Name: "岗位名称", Value: colPost(func(p *model.SysPost) any { return p.PostName })},
		{Name: "岗位排序", Value: colPost(func(p *model.SysPost) any { return p.PostSort })},
		{Name: "状态", Converter: "0=正常,1=停用", Value: colPost(func(p *model.SysPost) any { return p.Status })},
	}
}

// ExportPost 把岗位列表写成 xlsx 流：sheet 名 "岗位数据"（title 为空）。
func ExportPost(w io.Writer, list []model.SysPost) error {
	rows := make([]any, len(list))
	for i := range list {
		rows[i] = &list[i]
	}
	return WriteExcel(w, "岗位数据", postExportColumns(), rows)
}

// SelectPostById 对应 selectPostById：按主键查询单岗位（selectPostVo 不含 update_by/update_time → 保持零值）。不存在回 nil。
func SelectPostById(ctx context.Context, postId int64) *model.SysPost {
	rec, err := g.DB().GetOne(ctx, selectPostVo+" where post_id = ?", postId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var p model.SysPost
	if gconv.Struct(rec.Map(), &p) != nil {
		return nil
	}
	return &p
}

// uniquePostIdBy 复用 checkPostXxxUnique 的 limit 1 短路查询，返回占用该列的岗位 ID（无则 -1）。
func uniquePostIdBy(ctx context.Context, col, val string) int64 {
	if val == "" {
		return -1
	}
	rec, err := g.DB().GetOne(ctx, "select post_id from sys_post where "+col+" = ? limit 1", val)
	if err != nil || rec == nil || rec.IsEmpty() {
		return -1
	}
	return gconv.Int64(rec.Map()["post_id"])
}

// CheckPostNameUnique 对应 checkPostNameUnique（sys_post 无 del_flag，查询不带软删过滤）。
func CheckPostNameUnique(ctx context.Context, post *model.SysPost) bool {
	return uniqueSelfOrNone(post.PostId, uniquePostIdBy(ctx, "post_name", post.PostName))
}

// CheckPostCodeUnique 对应 checkPostCodeUnique。
func CheckPostCodeUnique(ctx context.Context, post *model.SysPost) bool {
	return uniqueSelfOrNone(post.PostId, uniquePostIdBy(ctx, "post_code", post.PostCode))
}

// CountUserPostById 对应 userPostMapper.countUserPostById：该岗位已关联的用户数（删除护栏）。
func CountUserPostById(ctx context.Context, postId int64) int {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_user_post where post_id = ?", postId)
	if err != nil || v.IsNil() {
		return 0
	}
	return gconv.Int(v)
}

// InsertPost 对应 insertPost：动态非空列插入（post_sort 经 @NotNull 必存在），create_time 刷新，回写自增 postId。
func InsertPost(ctx context.Context, post *model.SysPost, createBy string) int {
	cols := make([]string, 0, 7)
	vals := make([]interface{}, 0, 7)
	add := func(col string, val interface{}) {
		cols = append(cols, col)
		vals = append(vals, val)
	}
	if post.PostCode != "" {
		add("post_code", post.PostCode)
	}
	if post.PostName != "" {
		add("post_name", post.PostName)
	}
	add("post_sort", post.PostSort)
	if post.Status != "" {
		add("status", post.Status)
	}
	if post.Remark != "" {
		add("remark", post.Remark)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	add("create_time", gtime.Now())
	sql := "insert into sys_post(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	res, err := g.DB().Exec(ctx, sql, vals...)
	if err != nil {
		return 0
	}
	if id, e := res.LastInsertId(); e == nil {
		post.PostId = id
	}
	return affectedOf(res)
}

// UpdatePost 对应 updatePost：动态 SET 仅更新非空字段（post_sort 经 @NotNull 必存在），update_time 强制刷新。
func UpdatePost(ctx context.Context, post *model.SysPost, updateBy string) int {
	sets := make([]string, 0, 7)
	args := make([]interface{}, 0, 7)
	add := func(clause string, val interface{}) {
		sets = append(sets, clause)
		args = append(args, val)
	}
	if post.PostCode != "" {
		add("post_code = ?", post.PostCode)
	}
	if post.PostName != "" {
		add("post_name = ?", post.PostName)
	}
	add("post_sort = ?", post.PostSort)
	if post.Status != "" {
		add("status = ?", post.Status)
	}
	if post.Remark != "" {
		add("remark = ?", post.Remark)
	}
	if updateBy != "" {
		add("update_by = ?", updateBy)
	}
	sets = append(sets, "update_time = ?")
	args = append(args, gtime.Now())
	sql := "update sys_post set " + joinStrings(sets, ", ") + " where post_id = ?"
	args = append(args, post.PostId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0
	}
	return affectedOf(res)
}

// DeletePostByIds 对应 deletePostByIds：逐个校验已分配护栏（有用户关联即拒）→ 批量硬删（sys_post 无软删），返回真实影响行。
func DeletePostByIds(ctx context.Context, postIds []int64) (int, string) {
	for _, postId := range postIds {
		if CountUserPostById(ctx, postId) > 0 {
			name := ""
			if post := SelectPostById(ctx, postId); post != nil {
				name = post.PostName
			}
			return 0, fmt.Sprintf("%s已分配,不能删除", name)
		}
	}
	sql := "delete from sys_post where post_id in (" + placeholders(len(postIds)) + ")"
	res, err := g.DB().Exec(ctx, sql, idsToInterfaces(postIds)...)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}
