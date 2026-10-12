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
	"github.com/gogf/gf/v2/os/gctx"
	"github.com/gogf/gf/v2/os/glog"
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

// insertLogininforSQL 对应 SysLogininforMapper.xml insertLogininfor（8 列 + login_time=sysdate()）。
const insertLogininforSQL = "insert into sys_logininfor(user_name, status, ipaddr, login_location, browser, os, msg, login_time) " +
	"values(?, ?, ?, ?, ?, ?, ?, sysdate())"

// InsertLogininfor 对应 insertLogininfor：落一行登录/注册审计。
func InsertLogininfor(ctx context.Context, o *model.SysLogininfor) error {
	_, err := g.DB().Exec(ctx, insertLogininforSQL, o.UserName, o.Status, o.Ipaddr, o.LoginLocation, o.Browser, o.Os, o.Msg)
	return err
}

// capStr 对应 AsyncFactory.cap：去首尾空白后按列宽截断（按 rune 计，对齐 MySQL 字符列宽语义）。
func capStr(v string, maxLen int) string {
	t := strings.TrimSpace(v)
	if maxLen <= 0 {
		return t
	}
	r := []rune(t)
	if len(r) <= maxLen {
		return t
	}
	return string(r[:maxLen])
}

// RecordLogininfor 对应 AsyncFactory.recordLogininfor 的落库实体装配：从 UA 识 browser/os，
// 按列宽 cap 各串字段，status 由事件类型常量映射（LOGIN_SUCCESS/LOGOUT/REGISTER→成功，LOGIN_FAIL→失败）。
// login_location 与 operlog 同法留空（不复刻在线 IP→地址查询）。
func RecordLogininfor(ctx context.Context, username, event, message, ip, userAgent string) error {
	o := &model.SysLogininfor{
		UserName:      capStr(username, consts.LfUserNameLen),
		Ipaddr:        capStr(ip, consts.LfIpaddrLen),
		LoginLocation: "", // 对齐既有简化：不做在线 IP→地址查询
		Browser:       capStr(UserAgentBrowser(userAgent), consts.LfBrowserLen),
		Os:            capStr(UserAgentOS(userAgent), consts.LfOsLen),
		Msg:           capStr(message, consts.LfMsgLen),
	}
	switch event {
	case consts.EvLoginFail:
		o.Status = consts.LoginStatusFail
	default: // LOGIN_SUCCESS / LOGOUT / REGISTER 恒映射为成功（忠实 Java；注册成败由 msg 承载）
		o.Status = consts.LoginStatusSuccess
	}
	return InsertLogininfor(ctx, o)
}

// AsyncRecordLogininfor 异步写审计行：入参均为已拷贝的基元值（调用方在请求线程提前取 ip/UA），
// 故不触碰将归还对象池的 Request；用独立 gctx.New()、recover 兜底，失败仅记日志不影响业务响应。
func AsyncRecordLogininfor(username, event, message, ip, userAgent string) {
	go func() {
		defer func() {
			if e := recover(); e != nil {
				glog.Errorf(gctx.New(), "recordLogininfor panic: %v", e)
			}
		}()
		ctx := gctx.New()
		if err := RecordLogininfor(ctx, username, event, message, ip, userAgent); err != nil {
			glog.Errorf(ctx, "recordLogininfor insert failed user=%s event=%s: %v", username, event, err)
		}
	}()
}
