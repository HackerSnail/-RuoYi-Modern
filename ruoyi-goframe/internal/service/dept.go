// Package service —— /system/dept 管理端读写，逐条复刻 SysDeptServiceImpl + SysDeptMapper.xml 的真实语义
// （DataScope 注入、祖级链维护、唯一性/停用/环/数据权限校验、软删除）。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// DeptListFilter 部门列表条件（对应 SysDept 的 deptId/parentId/deptName/status）。
type DeptListFilter struct {
	DeptId   int64
	ParentId int64
	DeptName string
	Status   string
}

const selectDeptVo = "select d.dept_id, d.parent_id, d.ancestors, d.dept_name, d.order_num, d.leader, d.phone, d.email, d.status, d.del_flag, d.create_by, d.create_time from sys_dept d"

// SelectDeptList 对应 selectDeptList(dept)：del_flag='0' + 条件过滤 + DataScope 片段 + 排序。
// dataScope 由调用方经 BuildDataScope(lu,"system:dept:list","d","dept_id","","") 得到（admin 传空串）。
func SelectDeptList(ctx context.Context, f DeptListFilter, dataScope string) []*model.SysDept {
	sql := selectDeptVo + " where d.del_flag = '0'"
	args := make([]interface{}, 0, 4)
	if f.DeptId != 0 {
		sql += " and d.dept_id = ?"
		args = append(args, f.DeptId)
	}
	if f.ParentId != 0 {
		sql += " and d.parent_id = ?"
		args = append(args, f.ParentId)
	}
	if f.DeptName != "" {
		sql += " and d.dept_name like concat('%', '" + escLit(f.DeptName) + "', '%')"
	}
	if f.Status != "" {
		sql += " and d.status = '" + escLit(f.Status) + "'"
	}
	sql += " " + dataScope + " order by d.parent_id, d.order_num"
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanDepts(recs)
}

func scanDepts(recs gdb.Result) []*model.SysDept {
	var out []*model.SysDept
	for _, r := range recs {
		var d model.SysDept
		if err := gconv.Struct(r.Map(), &d); err == nil {
			d.Children = []*model.SysDept{}
			out = append(out, &d)
		}
	}
	return out
}

// SelectDeptById 对应 selectDeptById：单条 + 子查询 parent_name；children=[]。
func SelectDeptById(ctx context.Context, deptId int64) *model.SysDept {
	rec, err := g.DB().GetOne(ctx,
		"select d.dept_id, d.parent_id, d.ancestors, d.dept_name, d.order_num, d.leader, d.phone, d.email, d.status,"+
			" (select dept_name from sys_dept where dept_id = d.parent_id) parent_name from sys_dept d where d.dept_id = ?", deptId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDept
	if err := gconv.Struct(rec.Map(), &d); err != nil {
		return nil
	}
	d.Children = []*model.SysDept{}
	return &d
}

// HasChildByDeptId 对应 hasChildByDeptId（未删除子部门）。
func HasChildByDeptId(ctx context.Context, deptId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_dept where del_flag = '0' and parent_id = ? limit 1", deptId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// CheckDeptExistUser 对应 checkDeptExistUser（该部门下未删除用户）。
func CheckDeptExistUser(ctx context.Context, deptId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_user where dept_id = ? and del_flag = '0'", deptId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// SelectNormalChildrenDeptById 对应 selectNormalChildrenDeptById（正常状态未删除子孙数）。
func SelectNormalChildrenDeptById(ctx context.Context, deptId int64) int {
	v, err := g.DB().GetValue(ctx,
		"select count(*) from sys_dept where status = 0 and del_flag = '0' and find_in_set(?, ancestors)", deptId)
	if err != nil || v.IsNil() {
		return 0
	}
	return v.Int()
}

// CheckDeptNameUnique 对应 checkDeptNameUnique：同 parentId 下 deptName 唯一（排除自身，未删除）。
func CheckDeptNameUnique(ctx context.Context, dept *model.SysDept) bool {
	deptId := dept.DeptId
	rec, err := g.DB().GetOne(ctx, selectDeptVo+" where dept_name = ? and parent_id = ? and d.del_flag = '0' limit 1",
		dept.DeptName, dept.ParentId)
	if err == nil && rec != nil && !rec.IsEmpty() {
		var info model.SysDept
		if gconv.Struct(rec.Map(), &info) == nil && info.DeptId != deptId {
			return false
		}
	}
	return true
}

// CheckDeptDataScope 对应 checkDeptDataScope：非管理员且目标部门不在其数据范围内 → 返回错误文案；否则空串。
func CheckDeptDataScope(ctx context.Context, lu *model.LoginUser, permission string, deptId int64) string {
	if lu == nil || lu.User == nil || IsAdmin(lu.User.UserId) || deptId == 0 {
		return ""
	}
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "", "")
	list := SelectDeptList(ctx, DeptListFilter{DeptId: deptId}, scope)
	if len(list) == 0 {
		return "没有权限访问部门数据！"
	}
	return ""
}

// InsertDept 对应 insertDept：校验父节点数据范围/存在/状态，计算 ancestors 后插入。返回 (rows, 业务错误文案)。
func InsertDept(ctx context.Context, lu *model.LoginUser, dept *model.SysDept, createBy string) (int, string) {
	if msg := CheckDeptDataScope(ctx, lu, "system:dept:list", dept.ParentId); msg != "" {
		return 0, msg
	}
	info := SelectDeptById(ctx, dept.ParentId)
	if info == nil {
		return 0, "上级部门不存在或已被删除"
	}
	if info.Status != "0" {
		return 0, "部门停用，不允许新增"
	}
	dept.Ancestors = info.Ancestors + "," + gconv.String(dept.ParentId)
	_, err := g.DB().Exec(ctx,
		"insert into sys_dept(parent_id, ancestors, dept_name, order_num, leader, phone, email, status, del_flag, create_by, create_time)"+
			" values(?,?,?,?,?,?,?,?, '0', ?, sysdate())",
		dept.ParentId, dept.Ancestors, dept.DeptName, dept.OrderNum, dept.Leader, dept.Phone, dept.Email,
		defaultStr(dept.Status, "0"), createBy)
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// UpdateDept 对应 updateDept：自身/新父存在性、环检测、数据范围、祖级链重算 + 子孙 ancestors 维护。
func UpdateDept(ctx context.Context, lu *model.LoginUser, dept *model.SysDept, updateBy string) (int, string) {
	newParent := SelectDeptById(ctx, dept.ParentId)
	oldDept := SelectDeptById(ctx, dept.DeptId)
	if oldDept == nil {
		return 0, "部门不存在或已被删除"
	}
	if newParent == nil {
		return 0, "上级部门不存在或已被删除"
	}
	if dept.DeptId == dept.ParentId {
		return 0, "上级部门不能是自己"
	}
	if strings.Contains(","+newParent.Ancestors+",", ","+gconv.String(dept.DeptId)+",") {
		return 0, "上级部门不能是自己的下级部门"
	}
	if msg := CheckDeptDataScope(ctx, lu, "system:dept:list", dept.DeptId); msg != "" {
		return 0, msg
	}
	if msg := CheckDeptDataScope(ctx, lu, "system:dept:list", dept.ParentId); msg != "" {
		return 0, msg
	}
	newAncestors := newParent.Ancestors + "," + gconv.String(newParent.DeptId)
	oldAncestors := oldDept.Ancestors
	dept.Ancestors = newAncestors
	updateDeptChildren(ctx, dept.DeptId, newAncestors, oldAncestors)
	_, err := g.DB().Exec(ctx,
		"update sys_dept set parent_id=?, dept_name=?, ancestors=?, order_num=?, leader=?, phone=?, email=?, status=?, update_by=?, update_time=sysdate() where dept_id=?",
		dept.ParentId, dept.DeptName, dept.Ancestors, dept.OrderNum, dept.Leader, dept.Phone, dept.Email,
		defaultStr(dept.Status, "0"), updateBy, dept.DeptId)
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// updateDeptChildren 对应 updateDeptChildren：子孙 ancestors 前缀整体替换（非正则）。
func updateDeptChildren(ctx context.Context, deptId int64, newAncestors, oldAncestors string) {
	recs, err := g.DB().GetAll(ctx, "select dept_id, ancestors from sys_dept where find_in_set(?, ancestors)", deptId)
	if err != nil {
		return
	}
	for _, rec := range recs {
		m := rec.Map()
		anc := gconv.String(m["anc"])
		if anc == "" {
			anc = gconv.String(m["ancestors"])
		}
		if oldAncestors != "" && strings.HasPrefix(anc, oldAncestors) {
			na := newAncestors + anc[len(oldAncestors):]
			_, _ = g.DB().Exec(ctx, "update sys_dept set ancestors = ? where dept_id = ?", na, gconv.Int64(m["dept_id"]))
		}
	}
}

// UpdateDeptSort 对应 updateDeptSort：长度一致 + 逐项数据范围 + 批量 CASE WHEN。返回业务错误文案。
func UpdateDeptSort(ctx context.Context, lu *model.LoginUser, ids []int64, orders []int) string {
	if len(ids) != len(orders) {
		return "保存排序失败，部门与排序号数量不一致"
	}
	for _, id := range ids {
		if msg := CheckDeptDataScope(ctx, lu, "system:dept:list", id); msg != "" {
			return msg
		}
	}
	if len(ids) == 0 {
		return ""
	}
	caseSql := ""
	args := make([]interface{}, 0, len(ids)*2+len(ids))
	for i := range ids {
		caseSql += " when ? then ?"
		args = append(args, ids[i], orders[i])
	}
	args = append(args, idsToInterfaces(ids)...)
	sql := "update sys_dept set order_num = case dept_id" + caseSql + " else order_num end where dept_id in (" + placeholders(len(ids)) + ")"
	_, _ = g.DB().Exec(ctx, sql, args...)
	return ""
}

// DeleteDeptById 对应 deleteDeptById：数据范围 + 子部门/用户护栏后软删除（del_flag='2'）。
func DeleteDeptById(ctx context.Context, lu *model.LoginUser, deptId int64) (int, string) {
	if msg := CheckDeptDataScope(ctx, lu, "system:dept:list", deptId); msg != "" {
		return 0, msg
	}
	if HasChildByDeptId(ctx, deptId) {
		return 0, "存在下级部门，不允许删除"
	}
	if CheckDeptExistUser(ctx, deptId) {
		return 0, "部门存在用户，不允许删除"
	}
	_, err := g.DB().Exec(ctx, "update sys_dept set del_flag = '2' where dept_id = ?", deptId)
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// BuildDeptTreeSelect 对应 buildDeptTreeSelect：先 buildDeptTree 再转 TreeSelect（disabled=status停用）。
func BuildDeptTreeSelect(list []*model.SysDept) []*model.TreeSelect {
	trees := BuildDeptTree(list)
	out := make([]*model.TreeSelect, 0, len(trees))
	for _, d := range trees {
		out = append(out, deptToTreeSelect(d))
	}
	return out
}

// BuildDeptTree 对应 buildDeptTree：parentId 不在结果集 id 集合内者视为顶级；全空回原列表。
func BuildDeptTree(list []*model.SysDept) []*model.SysDept {
	idSet := map[int64]bool{}
	for _, d := range list {
		idSet[d.DeptId] = true
	}
	var res []*model.SysDept
	for _, d := range list {
		if !idSet[d.ParentId] {
			deptRecursionFn(list, d)
			res = append(res, d)
		}
	}
	if len(res) == 0 {
		return list
	}
	return res
}

func deptRecursionFn(list []*model.SysDept, t *model.SysDept) {
	children := deptChildList(list, t)
	t.Children = children
	for _, c := range children {
		if len(deptChildList(list, c)) > 0 {
			deptRecursionFn(list, c)
		}
	}
}

func deptChildList(list []*model.SysDept, t *model.SysDept) []*model.SysDept {
	var res []*model.SysDept
	for _, n := range list {
		if n.ParentId == t.DeptId {
			res = append(res, n)
		}
	}
	return res
}

func deptToTreeSelect(d *model.SysDept) *model.TreeSelect {
	ts := &model.TreeSelect{Id: d.DeptId, Label: d.DeptName, Disabled: d.Status == "1"}
	if len(d.Children) > 0 {
		children := make([]*model.TreeSelect, 0, len(d.Children))
		for _, c := range d.Children {
			children = append(children, deptToTreeSelect(c))
		}
		ts.Children = children
	}
	return ts
}

// ExcludeSubTree 对应 excludeChild：排除自身及子孙（ancestors 逗号串含 deptId）。deptId==0 视为不排除。
func ExcludeSubTree(list []*model.SysDept, deptId int64) []*model.SysDept {
	if deptId == 0 {
		return list
	}
	out := make([]*model.SysDept, 0, len(list))
	idStr := gconv.String(deptId)
	for _, d := range list {
		if d.DeptId == deptId {
			continue
		}
		if containsCommaToken(d.Ancestors, idStr) {
			continue
		}
		out = append(out, d)
	}
	return out
}

func containsCommaToken(ancestors, token string) bool {
	for _, p := range strings.Split(ancestors, ",") {
		if p == token {
			return true
		}
	}
	return false
}
