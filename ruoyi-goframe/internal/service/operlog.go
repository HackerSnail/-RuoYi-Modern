// Package service —— 操作日志 sys_oper_log 服务层，逐条对齐 SysOperLogServiceImpl + SysOperLogMapper.xml。
//
// 契约要点（不得错位）：
//   - 列表：operIp/title/operName 模糊、businessType 精确(presence 判定，0 合法)、businessTypes IN、status 精确(presence)、
//     params[beginTime]/[endTime] 走【裸比较】oper_time >= / <= ?（异于 config/user 的 date_format，照 Java 原样）；默认 oper_id desc。
//   - deleteOperLogByIds：空即拒；单次 >500 即拒（审计证据表政策护栏）；底层真实 DELETE → 返受影响行数，交 toAjax 判定。
//   - cleanOperLog：truncate 属 DDL 不返行数，故先 count 精确行数再 truncate，把条数回传给调用方写进 msg。
package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectOperLogVo 对应 SysOperLogMapper.xml <sql id="selectOperLogVo">（17 列，无 BaseEntity 额外列）。
const selectOperLogVo = "select oper_id, title, business_type, method, request_method, operator_type, " +
	"oper_name, dept_name, oper_url, oper_ip, oper_location, oper_param, json_result, status, error_msg, " +
	"oper_time, cost_time from sys_oper_log"

// operLogDeleteMaxBatch 批量删除单次上限，对齐 SysOperLogServiceImpl.DELETE_MAX_BATCH。
const operLogDeleteMaxBatch = 500

// OperLogListFilter 操作日志列表查询条件。HasBusinessType/HasStatus 用 presence 区分"未提交"与"提交 0"。
type OperLogListFilter struct {
	OperIp          string
	Title           string
	HasBusinessType bool
	BusinessType    int
	BusinessTypes   []int64
	HasStatus       bool
	Status          int
	OperName        string
	BeginTime       string
	EndTime         string
}

// buildOperLogListSQL 组装列表基础查询（<where> 逐条 AND，不含 ORDER BY，排序交 QueryPaged 依默认/覆盖列决定）。
func buildOperLogListSQL(f OperLogListFilter) (string, []interface{}) {
	sql := selectOperLogVo
	conds := make([]string, 0, 8)
	args := make([]interface{}, 0, 8)
	if f.OperIp != "" {
		conds = append(conds, "oper_ip like concat('%', ?, '%')")
		args = append(args, f.OperIp)
	}
	if f.Title != "" {
		conds = append(conds, "title like concat('%', ?, '%')")
		args = append(args, f.Title)
	}
	if f.HasBusinessType {
		conds = append(conds, "business_type = ?")
		args = append(args, f.BusinessType)
	}
	if len(f.BusinessTypes) > 0 {
		ph := make([]string, len(f.BusinessTypes))
		for i, bt := range f.BusinessTypes {
			ph[i] = "?"
			args = append(args, bt)
		}
		conds = append(conds, "business_type in ("+strings.Join(ph, ",")+")")
	}
	if f.HasStatus {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if f.OperName != "" {
		conds = append(conds, "oper_name like concat('%', ?, '%')")
		args = append(args, f.OperName)
	}
	if f.BeginTime != "" {
		conds = append(conds, "oper_time >= ?")
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		conds = append(conds, "oper_time <= ?")
		args = append(args, f.EndTime)
	}
	if len(conds) > 0 {
		sql += " where " + strings.Join(conds, " AND ")
	}
	return sql, args
}

func scanOperLogs(recs gdb.Result) []model.SysOperLog {
	out := make([]model.SysOperLog, 0, len(recs))
	for _, r := range recs {
		var o model.SysOperLog
		if gconv.Struct(r.Map(), &o) == nil {
			out = append(out, o)
		}
	}
	return out
}

// SelectOperLogListPaged 对应 list 端点 startPage + selectOperLogList（默认 oper_id desc，可被 orderByColumn 覆盖）。
func SelectOperLogListPaged(ctx context.Context, f OperLogListFilter, p Page) ([]model.SysOperLog, int64, error) {
	sql, args := buildOperLogListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "oper_id desc")
	if err != nil {
		return nil, 0, err
	}
	return scanOperLogs(res.Rows), res.Total, nil
}

// DeleteOperLogByIds 对应 deleteOperLogByIds：空/超限入口即拒（中文文案照 Java ServiceException），底层 DELETE 返受影响行数。
func DeleteOperLogByIds(ctx context.Context, operIds []int64) (int, error) {
	if len(operIds) == 0 {
		return 0, fmt.Errorf("删除的操作日志 ID 不能为空")
	}
	if len(operIds) > operLogDeleteMaxBatch {
		return 0, fmt.Errorf("单次删除操作日志不得超过 %d 条，当前 %d 条", operLogDeleteMaxBatch, len(operIds))
	}
	ph := make([]string, len(operIds))
	args := make([]interface{}, len(operIds))
	for i, id := range operIds {
		ph[i] = "?"
		args[i] = id
	}
	sql := "delete from sys_oper_log where oper_id in (" + strings.Join(ph, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// CleanOperLog 对应 cleanOperLog：先 count 精确行数，再 truncate（DDL 不返行数）。返回清空前的精确条数。
func CleanOperLog(ctx context.Context) (int, error) {
	v, err := g.DB().GetValue(ctx, "select count(*) from sys_oper_log")
	if err != nil {
		return 0, err
	}
	count := v.Int()
	if _, err = g.DB().Exec(ctx, "truncate table sys_oper_log"); err != nil {
		return 0, err
	}
	return count, nil
}

// insertOperLogSQL 对应 SysOperLogMapper.xml <insert id="insertOperlog">：16 列、oper_time 走 sysdate()。
// 列顺序与取值须与 Java 逐字对齐（title, business_type, method, request_method, operator_type,
// oper_name, dept_name, oper_url, oper_ip, oper_location, oper_param, json_result, status, error_msg, cost_time, oper_time）。
const insertOperLogSQL = "insert into sys_oper_log(title, business_type, method, request_method, operator_type, " +
	"oper_name, dept_name, oper_url, oper_ip, oper_location, oper_param, json_result, status, error_msg, cost_time, oper_time) " +
	"values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, sysdate())"

// InsertOperLog 对应 insertOperlog：由 @Log 切面等价的中间件在异步协程中调用，把快照好的操作日志落库。
// 调用方须已完成各列宽 cap/截断（对齐 AsyncFactory）；此处不再重复裁剪，只做纯插入。
func InsertOperLog(ctx context.Context, o *model.SysOperLog) error {
	_, err := g.DB().Exec(ctx, insertOperLogSQL,
		o.Title, o.BusinessType, o.Method, o.RequestMethod, o.OperatorType,
		o.OperName, o.DeptName, o.OperUrl, o.OperIp, o.OperLocation,
		o.OperParam, o.JsonResult, o.Status, o.ErrorMsg, o.CostTime)
	return err
}
