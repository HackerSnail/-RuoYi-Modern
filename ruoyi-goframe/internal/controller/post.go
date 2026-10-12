// Package controller —— /system/post 管理端 HTTP 处理器，逐条对齐 SysPostController 的入参/出参/校验顺序/文案。
// 岗位无数据权限（SysPostMapper 无 ${dataScope}），删除护栏为"已分配用户不可删"；Excel 导出属后续批次。
package controller

import (
	"io"
	"regexp"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// 复刻 SysPost 的 @Pattern 约束（岗位编码字符集 / 状态取值）。各端点 @PreAuthorize 权限在 router 里以 middleware.Permi 字面量声明（岗位无 DataScope，故无需本地权限常量）。
var (
	postCodePattern   = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	postStatusPattern = regexp.MustCompile(`^[01]$`)
)

// PostList GET /system/post/list（system:post:list）→ TableDataInfo（分页，无 DataScope）。
func PostList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.PostListFilter{
		PostCode: r.Get("postCode").String(),
		Status:   r.Get("status").String(),
		PostName: r.Get("postName").String(),
	}
	rows, total, err := service.SelectPostListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// PostExport POST /system/post/export（system:post:export + @Log EXPORT）：同 list 的过滤入参（岗位无 DataScope），
// 过 exportLimit 护栏后写 xlsx。
func PostExport(r *ghttp.Request) {
	ctx := r.Context()
	f := service.PostListFilter{
		PostCode: r.Get("postCode").String(),
		Status:   r.Get("status").String(),
		PostName: r.Get("postName").String(),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectPostListLimited(ctx, f, max)
	if err != nil {
		api.Send(r, api.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportPost(w, rows) })
}

// PostGetInfo GET /system/post/{postId}（system:post:query）→ data:{post}。
func PostGetInfo(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectPostById(r.Context(), r.Get("postId").Int64())))
}

// PostOptionSelect GET /system/post/optionselect（Java 无 @PreAuthorize，仅需登录态）→ data:[SysPost...]。
func PostOptionSelect(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectPostAll(r.Context())))
}

// PostAdd POST /system/post（system:post:add）。@Validated → 名称唯一 → 编码唯一 → insertPost。
func PostAdd(r *ghttp.Request) {
	var post model.SysPost
	if err := r.Parse(&post); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validatePost(&post, postSortPresent(r), postStatusPresent(r)); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckPostNameUnique(ctx, &post) {
		api.Send(r, api.Err("新增岗位'"+post.PostName+"'失败，岗位名称已存在"))
		return
	}
	if !service.CheckPostCodeUnique(ctx, &post) {
		api.Send(r, api.Err("新增岗位'"+post.PostName+"'失败，岗位编码已存在"))
		return
	}
	api.Send(r, toAjax(service.InsertPost(ctx, &post, lu.Username)))
}

// PostEdit PUT /system/post（system:post:edit）。@Validated → 名称唯一（排除自身）→ 编码唯一 → updatePost。
func PostEdit(r *ghttp.Request) {
	var post model.SysPost
	if err := r.Parse(&post); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validatePost(&post, postSortPresent(r), postStatusPresent(r)); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckPostNameUnique(ctx, &post) {
		api.Send(r, api.Err("修改岗位'"+post.PostName+"'失败，岗位名称已存在"))
		return
	}
	if !service.CheckPostCodeUnique(ctx, &post) {
		api.Send(r, api.Err("修改岗位'"+post.PostName+"'失败，岗位编码已存在"))
		return
	}
	api.Send(r, toAjax(service.UpdatePost(ctx, &post, lu.Username)))
}

// PostRemove DELETE /system/post/{postIds}（system:post:remove）。逐个已分配护栏后批量硬删。
func PostRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.Get("postIds").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("岗位 ID 非法"))
		return
	}
	rows, msg := service.DeletePostByIds(ctx, ids)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// postSortPresent 判定 body 是否显式携带 postSort（对齐 @NotNull：缺字段即违规，0 仍合法）。
func postSortPresent(r *ghttp.Request) bool {
	v := r.Get("postSort")
	return !v.IsNil() && v.String() != ""
}

// postStatusPresent 判定 body 是否显式携带非空 status（@Pattern 仅在传值时校验）。
func postStatusPresent(r *ghttp.Request) bool {
	v := r.Get("status")
	return !v.IsNil() && v.String() != ""
}

// validatePost 复刻 SysPost 的 @Validated 字段约束（编码 NotBlank+Size64+Pattern、名称 NotBlank+Size50、排序 NotNull+Min0、状态 Pattern）。
func validatePost(p *model.SysPost, sortPresent, statusPresent bool) []violation {
	var vs []violation
	if p.PostCode == "" {
		vs = append(vs, violation{"postCode", "岗位编码不能为空"})
	} else {
		if runeLen(p.PostCode) > 64 {
			vs = append(vs, violation{"postCode", "岗位编码长度不能超过64个字符"})
		}
		if !postCodePattern.MatchString(p.PostCode) {
			vs = append(vs, violation{"postCode", "岗位编码只能包含字母、数字、下划线和短横线"})
		}
	}
	if p.PostName == "" {
		vs = append(vs, violation{"postName", "岗位名称不能为空"})
	} else if runeLen(p.PostName) > 50 {
		vs = append(vs, violation{"postName", "岗位名称长度不能超过50个字符"})
	}
	if !sortPresent {
		vs = append(vs, violation{"postSort", "显示顺序不能为空"})
	} else if p.PostSort < 0 {
		vs = append(vs, violation{"postSort", "显示顺序不能小于0"})
	}
	if statusPresent && !postStatusPattern.MatchString(p.Status) {
		vs = append(vs, violation{"status", "岗位状态只能为0或1"})
	}
	return vs
}
