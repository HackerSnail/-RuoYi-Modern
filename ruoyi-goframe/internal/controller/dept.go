// Package controller —— /system/dept 管理端 HTTP 处理器，逐条对齐 SysDeptController 的入参/出参/校验顺序/文案 + DataScope。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

const deptPermiList = "system:dept:list"

// deptScope 计算当前会话对部门查询的数据范围片段（admin 返回空串）。
func deptScope(r *ghttp.Request) string {
	lu := middleware.CurrentLoginUser(r)
	return service.BuildDataScope(r.Context(), lu, deptPermiList, "d", "dept_id", "", "")
}

// DeptList GET /system/dept/list（system:dept:list）→ data:[SysDept...]（flat + DataScope）。
func DeptList(r *ghttp.Request) {
	f := service.DeptListFilter{
		DeptId:   r.Get("deptId").Int64(),
		ParentId: r.Get("parentId").Int64(),
		DeptName: r.Get("deptName").String(),
		Status:   r.Get("status").String(),
	}
	list := service.SelectDeptList(r.Context(), f, deptScope(r))
	if list == nil {
		list = []*model.SysDept{}
	}
	response.Send(r, response.OkData(list))
}

// DeptExclude GET /system/dept/list/exclude/{deptId}（system:dept:list）→ 排除自身及子孙后的全量列表。
func DeptExclude(r *ghttp.Request) {
	ctx := r.Context()
	deptId := r.Get("deptId").Int64()
	list := service.SelectDeptList(ctx, service.DeptListFilter{}, deptScope(r))
	list = service.ExcludeSubTree(list, deptId)
	if list == nil {
		list = []*model.SysDept{}
	}
	response.Send(r, response.OkData(list))
}

// DeptGetInfo GET /system/dept/{deptId}（system:dept:query）→ data:{dept}；数据范围外回 code500 文案。
func DeptGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	deptId := r.Get("deptId").Int64()
	// 对齐 selectDeptById 内部先 checkDeptDataScope
	if msg := service.CheckDeptDataScope(ctx, lu, deptPermiList, deptId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, response.OkData(service.SelectDeptById(ctx, deptId)))
}

// DeptAdd POST /system/dept（system:dept:add）。@Validated → 名称唯一 → insertDept(数据范围/父校验)。
func DeptAdd(r *ghttp.Request) {
	var dept model.SysDept
	if err := r.Parse(&dept); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateDept(&dept); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDeptNameUnique(ctx, &dept) {
		response.Send(r, response.Err("新增部门'"+dept.DeptName+"'失败，部门名称已存在"))
		return
	}
	rows, msg := service.InsertDept(ctx, lu, &dept, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// DeptEdit PUT /system/dept（system:dept:edit）。@Validated → 名称唯一 → 停用含启用子拦截 → updateDept(环/范围)。
func DeptEdit(r *ghttp.Request) {
	var dept model.SysDept
	if err := r.Parse(&dept); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateDept(&dept); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDeptNameUnique(ctx, &dept) {
		response.Send(r, response.Err("修改部门'"+dept.DeptName+"'失败，部门名称已存在"))
		return
	}
	if dept.Status == "1" && service.SelectNormalChildrenDeptById(ctx, dept.DeptId) > 0 {
		response.Send(r, response.Err("该部门包含未停用的子部门！"))
		return
	}
	rows, msg := service.UpdateDept(ctx, lu, &dept, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// DeptUpdateSort PUT /system/dept/updateSort（system:dept:edit）。入参 {deptIds,orderNums} 逗号串。
func DeptUpdateSort(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	ids, ok1 := splitCommaInts(r.Get("deptIds").String())
	orders, ok2 := splitCommaInts(r.Get("orderNums").String())
	if !ok1 || !ok2 {
		response.Send(r, response.Err("保存排序失败，部门 ID 非法"))
		return
	}
	ordInts := make([]int, 0, len(orders))
	for _, o := range orders {
		ordInts = append(ordInts, int(o))
	}
	if msg := service.UpdateDeptSort(ctx, lu, ids, ordInts); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, response.Ok())
}

// DeptRemove DELETE /system/dept/{deptId}（system:dept:remove）。有下级/有用户 → warn(601)。
func DeptRemove(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	deptId := r.Get("deptId").Int64()
	if service.HasChildByDeptId(ctx, deptId) {
		response.Send(r, response.Warn("存在下级部门,不允许删除"))
		return
	}
	if service.CheckDeptExistUser(ctx, deptId) {
		response.Send(r, response.Warn("部门存在用户,不允许删除"))
		return
	}
	rows, msg := service.DeleteDeptById(ctx, lu, deptId)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// validateDept 复刻 SysDept 的 @Validated 字段约束（NotBlank/Size/Email），返回违规列表。
func validateDept(d *model.SysDept) []violation {
	var vs []violation
	if d.DeptName == "" {
		vs = append(vs, violation{"deptName", "部门名称不能为空"})
	} else if runeLen(d.DeptName) > 30 {
		vs = append(vs, violation{"deptName", "部门名称长度不能超过30个字符"})
	}
	if runeLen(d.Phone) > 11 {
		vs = append(vs, violation{"phone", "联系电话长度不能超过11个字符"})
	}
	if d.Email != "" && !isEmail(d.Email) {
		vs = append(vs, violation{"email", "邮箱格式不正确"})
	} else if runeLen(d.Email) > 50 {
		vs = append(vs, violation{"email", "邮箱长度不能超过50个字符"})
	}
	return vs
}

// isEmail 极简邮箱判定（含一个 @ 且前后非空），对齐 @Email 语义的常见情形。
func isEmail(s string) bool {
	at := -1
	for i := 0; i < len(s); i++ {
		if s[i] == '@' {
			if at >= 0 {
				return false
			}
			at = i
		}
	}
	return at > 0 && at < len(s)-1
}
