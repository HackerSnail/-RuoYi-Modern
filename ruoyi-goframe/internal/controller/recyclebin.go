// Package controller —— 回收站 /system/recyclebin。对齐 SysRecycleBinController 的 list/restore/purge。
//
// 鉴权采"白名单先于权限串"：{type} 运行时才确定，而权限串须据枚举派生，故在方法体内程序化完成——
// 先 ParseRecycleBinType 校验（非法 type 直接拒，非 500），再据枚举派生的固定权限串判 HasPermission（越权 403），
// 杜绝把用户输入拼进鉴权。登录态由路由级 Auth 保证；未登录 CurrentLoginUser 为空 → 401。
// restore/purge 返回逐项结果 RecycleBinOpResult，部分成功以 HTTP 200 承载。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// recycleBinBatchReq 回收站批量操作请求体（对齐 RecycleBinBatchReq：restore 仅 ids；purge 额外 confirm）。
type recycleBinBatchReq struct {
	Ids     []int64 `json:"ids"`
	Confirm *bool   `json:"confirm"`
}

// rbResolveType 依次：取会话（nil→401）→ 解析并白名单校验 {type}（非法→业务错误）。
// 权限串据类型派生，故由各 handler 自行判定（白名单已先于权限串，杜绝把输入拼进鉴权）。
// 任一校验不过已写响应并回 ok=false。
func rbResolveType(r *ghttp.Request) (service.RecycleBinType, *model.LoginUser, bool) {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil {
		response.SendAuthError(r)
		return service.RecycleBinType{}, nil, false
	}
	typeStr := r.GetRouter("type").String()
	t, ok := service.ParseRecycleBinType(typeStr)
	if !ok {
		response.Send(r, response.Err("非法的回收站类型："+typeStr))
		return service.RecycleBinType{}, nil, false
	}
	return t, lu, true
}

// rbDeny 判当前会话是否具备 permission；不足写 403 并回 false。
func rbDeny(r *ghttp.Request, lu *model.LoginUser, permission string) bool {
	if !service.HasPermission(lu.Permissions, permission) {
		response.SendForbidden(r, "没有权限，请联系管理员授权")
		return false
	}
	return true
}

// RecycleBinList GET /system/recyclebin/{type}/list?keyword → TableDataInfo（分页 + DataScope，仅 del_flag='2'）。
func RecycleBinList(r *ghttp.Request) {
	ctx := r.Context()
	t, lu, ok := rbResolveType(r)
	if !ok || !rbDeny(r, lu, service.RecycleBinListPermission()) {
		return
	}
	listPermi := service.RecycleBinListPermission()
	scope := service.BuildDataScope(ctx, lu, listPermi, "d", "dept_id", "u", "user_id")
	keyword := r.Get("keyword").String()
	p := service.ParsePage(r)
	var (
		rows  interface{}
		total int64
		err   error
	)
	switch t.Code() {
	case "user":
		rows, total, err = service.SelectDeletedUserListPaged(ctx, service.UserListFilter{UserName: keyword}, scope, p)
	case "role":
		rows, total, err = service.SelectDeletedRoleListPaged(ctx, service.RoleListFilter{RoleName: keyword},
			service.BuildDataScope(ctx, lu, listPermi, "d", "dept_id", "", ""), p)
	case "dept":
		rows, total, err = service.SelectDeletedDeptListPaged(ctx, service.DeptListFilter{DeptName: keyword},
			service.BuildDataScope(ctx, lu, listPermi, "d", "dept_id", "", ""), p)
	default:
		response.Send(r, response.Err("不支持的回收站类型："+t.Code()))
		return
	}
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// RecycleBinRestore PUT /system/recyclebin/{type}/restore body{ids} → AjaxResult(success=RecycleBinOpResult)。
func RecycleBinRestore(r *ghttp.Request) {
	ctx := r.Context()
	t, lu, ok := rbResolveType(r)
	if !ok {
		return
	}
	perm := t.RestorePermission()
	if !rbDeny(r, lu, perm) {
		return
	}
	var req recycleBinBatchReq
	if err := r.Parse(&req); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	result := service.Restore(ctx, lu, t, perm, req.Ids)
	response.Send(r, response.OkData(result))
}

// RecycleBinPurge DELETE /system/recyclebin/{type}/purge body{ids,confirm} → AjaxResult(success=RecycleBinOpResult)。
// confirm 非 true 整请求拒绝（对齐 Java purge 循环前的 ServiceException 抛出）。
func RecycleBinPurge(r *ghttp.Request) {
	ctx := r.Context()
	t, lu, ok := rbResolveType(r)
	if !ok {
		return
	}
	perm := t.PurgePermission()
	if !rbDeny(r, lu, perm) {
		return
	}
	var req recycleBinBatchReq
	if err := r.Parse(&req); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	confirm := req.Confirm != nil && *req.Confirm
	result, errMsg := service.Purge(ctx, lu, t, perm, req.Ids, confirm)
	if errMsg != "" {
		response.Send(r, response.Err(errMsg))
		return
	}
	response.Send(r, response.OkData(result))
}
