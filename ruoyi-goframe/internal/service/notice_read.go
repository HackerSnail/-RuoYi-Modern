// Package service —— sys_notice_read 公告已读追踪子系统（C2）。对齐 Java SysNoticeReadServiceImpl +
// SysNoticeReadMapper.xml：markRead / markReadBatch（insert ignore 幂等）、selectNoticeListWithReadStatus
// （LEFT JOIN 标 isRead、SQL 内 limit）、selectReadUsers/UnreadUsers（Map 行 + 分页）、deleteByNoticeIds。
// 取舍：C1 的 DeleteNoticeByIds 已在事务内级联清 sys_notice_read；此处 DeleteNoticeReadByNoticeIds 供 /repush 独立调用。
package service

import (
	"context"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"

	"ruoyi-goframe/internal/model"
)

// MarkRead 对应 insertNoticeRead：insert ignore into sys_notice_read，(user_id,notice_id) 唯一索引幂等去重，重复阅读不报错。
func MarkRead(ctx context.Context, noticeId, userId int64) {
	_, _ = g.DB().Exec(ctx,
		"insert ignore into sys_notice_read (notice_id, user_id, read_time) values(?, ?, ?)",
		noticeId, userId, gtime.Now())
}

// MarkReadBatch 对应 insertNoticeReadBatch：多值 insert ignore，一次网络往返写入并幂等去重。空入参无副作用。
func MarkReadBatch(ctx context.Context, userId int64, noticeIds []int64) {
	if len(noticeIds) == 0 {
		return
	}
	parts := make([]string, 0, len(noticeIds))
	args := make([]interface{}, 0, len(noticeIds)*3)
	for _, id := range noticeIds {
		parts = append(parts, "(?, ?, ?)")
		args = append(args, id, userId, gtime.Now())
	}
	sql := "insert ignore into sys_notice_read (notice_id, user_id, read_time) values " + joinStrings(parts, ", ")
	_, _ = g.DB().Exec(ctx, sql, args...)
}

// SelectNoticeListWithReadStatus 对应 selectNoticeListWithReadStatus：LEFT JOIN 已读表命中则 isRead=true；
// 仅取 status='0' 并按 notice_id desc，limit 在 SQL 内限制条数。投影列严格照 Java（不含 content/update*/remark，
// 未取列保持实体零值：字符串 ""、时间 nil）。
func SelectNoticeListWithReadStatus(ctx context.Context, userId int64, limit int) []model.SysNotice {
	sql := "select n.notice_id as noticeId, n.notice_title as noticeTitle, n.notice_type as noticeType, " +
		"n.status, n.create_by as createBy, n.create_time as createTime, " +
		"case when r.notice_id is not null then true else false end as isRead " +
		"from sys_notice n left join sys_notice_read r on r.notice_id = n.notice_id and r.user_id = ? " +
		"where n.status = '0' order by n.notice_id desc limit ?"
	recs, err := g.DB().GetAll(ctx, sql, userId, limit)
	if err != nil {
		return nil
	}
	return scanNotices(recs)
}

// buildReadUsersSQL 对应 selectReadUsersByNoticeId：已读表 inner join 用户(未删除) left join 部门，支持登录名/昵称模糊。
func buildReadUsersSQL(noticeId int64, searchValue string) (string, []interface{}) {
	sql := "select u.user_id as userId, u.user_name as userName, u.nick_name as nickName, d.dept_name as deptName, " +
		"u.phonenumber as phonenumber, r.read_time as readTime " +
		"from sys_notice_read r inner join sys_user u on u.user_id = r.user_id and u.del_flag = '0' " +
		"left join sys_dept d on d.dept_id = u.dept_id where r.notice_id = ?"
	args := []interface{}{noticeId}
	if searchValue != "" {
		sql += " and ( u.user_name like concat('%', ?, '%') or u.nick_name like concat('%', ?, '%') )"
		args = append(args, searchValue, searchValue)
	}
	return sql, args
}

// SelectReadUsersPaged 对应 readUsers/list startPage + selectReadUsersByNoticeId（order r.read_time desc）。
func SelectReadUsersPaged(ctx context.Context, p Page, noticeId int64, searchValue string) ([]g.Map, int64, error) {
	sql, args := buildReadUsersSQL(noticeId, searchValue)
	res, err := QueryPaged(ctx, p, sql, args, "r.read_time desc")
	if err != nil {
		return nil, 0, err
	}
	return scanMaps(res.Rows), res.Total, nil
}

// buildUnreadUsersSQL 对应 selectUnreadUsersByNoticeId（催读）：启用且未删除用户中不存在该公告已读记录者；与已读对称。
func buildUnreadUsersSQL(noticeId int64, searchValue string) (string, []interface{}) {
	sql := "select u.user_id as userId, u.user_name as userName, u.nick_name as nickName, d.dept_name as deptName, " +
		"u.phonenumber as phonenumber " +
		"from sys_user u left join sys_dept d on d.dept_id = u.dept_id " +
		"where u.del_flag = '0' and u.status = '0' and not exists " +
		"(select 1 from sys_notice_read r where r.notice_id = ? and r.user_id = u.user_id)"
	args := []interface{}{noticeId}
	if searchValue != "" {
		sql += " and ( u.user_name like concat('%', ?, '%') or u.nick_name like concat('%', ?, '%') )"
		args = append(args, searchValue, searchValue)
	}
	return sql, args
}

// SelectUnreadUsersPaged 对应 unreadUsers/list startPage + selectUnreadUsersByNoticeId（order u.user_id）。
func SelectUnreadUsersPaged(ctx context.Context, p Page, noticeId int64, searchValue string) ([]g.Map, int64, error) {
	sql, args := buildUnreadUsersSQL(noticeId, searchValue)
	res, err := QueryPaged(ctx, p, sql, args, "u.user_id")
	if err != nil {
		return nil, 0, err
	}
	return scanMaps(res.Rows), res.Total, nil
}

// DeleteNoticeReadByNoticeIds 对应 deleteByNoticeIds：按公告 ID 清理其全部已读记录（供 /repush 全员重读）。
func DeleteNoticeReadByNoticeIds(ctx context.Context, noticeIds []int64) int {
	if len(noticeIds) == 0 {
		return 0
	}
	res, err := g.DB().Exec(ctx, "delete from sys_notice_read where notice_id in ("+placeholders(len(noticeIds))+")", idsToInterfaces(noticeIds)...)
	if err != nil {
		return 0
	}
	return affectedOf(res)
}

// scanMaps 将查询结果逐行转为 g.Map（保留 SQL 别名为键；datetime 值为 *gtime.Time，序列化即 yyyy-MM-dd HH:mm:ss）。
func scanMaps(recs gdb.Result) []g.Map {
	out := make([]g.Map, 0, len(recs))
	for _, r := range recs {
		out = append(out, g.Map(r.Map()))
	}
	return out
}
