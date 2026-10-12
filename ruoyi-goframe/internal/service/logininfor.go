// Package service —— 登录日志 sys_logininfor 服务层，逐条对齐 SysLogininforServiceImpl + SysLogininforMapper.xml。
//
// 契约要点：
//   - 列表：ipaddr/userName 模糊、status(String) 精确(非空即滤)、时间范围【裸比较】login_time >= / <= ?；默认 info_id desc。
//   - deleteLogininforByIds：空即拒（无 operlog 那样的 500 上限）；底层 DELETE，rows==0 抛"删除失败，所选登录日志可能已被清空"。
//   - cleanLogininfor：truncate 是 DDL 且与 count 非原子，故取 information_schema.tables.table_rows 估算并标注"约"，弃用精确 count。
//   - clearLoginRecordCache：UNLINK pwd_err_cnt:{userName}（key 不存在安全返回，无需前置 hasKey）。
package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// selectLogininforVo 对应 SysLogininforMapper.xml 列表列（9 列）。
const selectLogininforVo = "select info_id, user_name, ipaddr, login_location, browser, os, status, msg, login_time from sys_logininfor"

// LogininforListFilter 登录日志列表查询条件。
type LogininforListFilter struct {
	Ipaddr    string
	Status    string
	UserName  string
	BeginTime string
	EndTime   string
}

// buildLogininforListSQL 组装列表基础查询（不含 ORDER BY）。
func buildLogininforListSQL(f LogininforListFilter) (string, []interface{}) {
	sql := selectLogininforVo
	conds := make([]string, 0, 5)
	args := make([]interface{}, 0, 5)
	if f.Ipaddr != "" {
		conds = append(conds, "ipaddr like concat('%', ?, '%')")
		args = append(args, f.Ipaddr)
	}
	if f.Status != "" {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if f.UserName != "" {
		conds = append(conds, "user_name like concat('%', ?, '%')")
		args = append(args, f.UserName)
	}
	if f.BeginTime != "" {
		conds = append(conds, "login_time >= ?")
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		conds = append(conds, "login_time <= ?")
		args = append(args, f.EndTime)
	}
	if len(conds) > 0 {
		sql += " where " + strings.Join(conds, " AND ")
	}
	return sql, args
}

func scanLogininfors(recs gdb.Result) []model.SysLogininfor {
	out := make([]model.SysLogininfor, 0, len(recs))
	for _, r := range recs {
		var o model.SysLogininfor
		if gconv.Struct(r.Map(), &o) == nil {
			out = append(out, o)
		}
	}
	return out
}

// SelectLogininforListPaged 对应 list 端点 startPage + selectLogininforList。
func SelectLogininforListPaged(ctx context.Context, f LogininforListFilter, p Page) ([]model.SysLogininfor, int64, error) {
	sql, args := buildLogininforListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "info_id desc")
	if err != nil {
		return nil, 0, err
	}
	return scanLogininfors(res.Rows), res.Total, nil
}

// DeleteLogininforByIds 对应 deleteLogininforByIds：空即拒；实删 0 行抛错（所选可能已被清空）。
func DeleteLogininforByIds(ctx context.Context, infoIds []int64) (int, error) {
	if len(infoIds) == 0 {
		return 0, fmt.Errorf("删除的登录日志 ID 不能为空")
	}
	ph := make([]string, len(infoIds))
	args := make([]interface{}, len(infoIds))
	for i, id := range infoIds {
		ph[i] = "?"
		args[i] = id
	}
	sql := "delete from sys_logininfor where info_id in (" + strings.Join(ph, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		return 0, fmt.Errorf("删除失败，所选登录日志可能已被清空")
	}
	return int(affected), nil
}

// CleanLogininfor 对应 cleanLogininfor：取 information_schema 估算行数 → truncate → 返回约行数（估算）。
func CleanLogininfor(ctx context.Context) (int, error) {
	v, err := g.DB().GetValue(ctx, "select table_rows from information_schema.tables where table_schema = database() and table_name = 'sys_logininfor'")
	approx := 0
	if err == nil && !v.IsNil() {
		approx = v.Int()
	}
	if _, err = g.DB().Exec(ctx, "truncate table sys_logininfor"); err != nil {
		return 0, err
	}
	return approx, nil
}

// ClearLoginRecordCache 对应 SysPasswordService.clearLoginRecordCache：清除该用户密码错误次数缓存，解除锁定。
func ClearLoginRecordCache(ctx context.Context, userName string) {
	_, _ = g.Redis().Unlink(ctx, consts.RedisPwdErrCnt+userName)
}
