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
	"io"
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

// SelectOperLogListLimited 对应 exportLimit 包裹的 selectOperLogList（无 DataScope，默认 oper_id desc）：同列表 SQL + 排序 + limit max+1。
func SelectOperLogListLimited(ctx context.Context, f OperLogListFilter, maxRows int) ([]model.SysOperLog, error) {
	sql, args := buildOperLogListSQL(f)
	sql += " order by oper_id desc limit ?"
	args = append(args, maxRows+1)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanOperLogs(recs), nil
}

// colOperLog 把「从 *model.SysOperLog 取值」的闭包适配成引擎的 func(any) any。
func colOperLog(fn func(*model.SysOperLog) any) func(any) any {
	return func(row any) any {
		o, ok := row.(*model.SysOperLog)
		if !ok || o == nil {
			return nil
		}
		return fn(o)
	}
}

// operLogExportColumns 操作日志导出列：严格按 SysOperLog 的 @Excel 声明序（businessTypes 数组无 @Excel 不导出）。
// operId 为 NUMERIC；businessType/operatorType/status 走 readConverterExp；operTime 为日期(width30)；costTime 带 suffix。
func operLogExportColumns() []ExcelColumn {
	return []ExcelColumn{
		{Name: "操作序号", Kind: ExcelKindNumeric, Value: colOperLog(func(o *model.SysOperLog) any { return o.OperId })},
		{Name: "操作模块", Value: colOperLog(func(o *model.SysOperLog) any { return o.Title })},
		{Name: "业务类型", Converter: "0=其它,1=新增,2=修改,3=删除,4=授权,5=导出,6=导入,7=强退,8=生成代码,9=清空数据", Value: colOperLog(func(o *model.SysOperLog) any { return o.BusinessType })},
		{Name: "请求方法", Value: colOperLog(func(o *model.SysOperLog) any { return o.Method })},
		{Name: "请求方式", Value: colOperLog(func(o *model.SysOperLog) any { return o.RequestMethod })},
		{Name: "操作类别", Converter: "0=其它,1=后台用户,2=手机端用户", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperatorType })},
		{Name: "操作人员", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperName })},
		{Name: "部门名称", Value: colOperLog(func(o *model.SysOperLog) any { return o.DeptName })},
		{Name: "请求地址", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperUrl })},
		{Name: "操作地址", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperIp })},
		{Name: "操作地点", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperLocation })},
		{Name: "请求参数", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperParam })},
		{Name: "返回参数", Value: colOperLog(func(o *model.SysOperLog) any { return o.JsonResult })},
		{Name: "状态", Converter: "0=正常,1=异常", Value: colOperLog(func(o *model.SysOperLog) any { return o.Status })},
		{Name: "错误消息", Value: colOperLog(func(o *model.SysOperLog) any { return o.ErrorMsg })},
		{Name: "操作时间", Width: 30, Kind: ExcelKindDate, Layout: "2006-01-02 15:04:05", Value: colOperLog(func(o *model.SysOperLog) any { return o.OperTime })},
		{Name: "消耗时间", Suffix: "毫秒", Value: colOperLog(func(o *model.SysOperLog) any { return o.CostTime })},
	}
}

// ExportOperLog 把操作日志列表写成 xlsx 流：sheet 名 "操作日志"（title 为空）。
func ExportOperLog(w io.Writer, list []model.SysOperLog) error {
	rows := make([]any, len(list))
	for i := range list {
		rows[i] = &list[i]
	}
	return WriteExcel(w, "操作日志", operLogExportColumns(), rows)
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
