// Package service —— sys_notice 通知公告（C1 核心 CRUD）。对齐 Java SysNoticeServiceImpl：
// selectNoticeById / selectNoticeList / insertNotice / updateNotice / deleteNoticeByIds。
// 取舍：C1 只做公告本体 CRUD；已读追踪子系统（listTop/markRead/repush/已读未读用户）在 C2。
// deleteNoticeByIds 与 Java 一致——@Transactional 内先级联清理 sys_notice_read 已读记录，再删本体，
// 避免“清了已读、删本体失败”导致公告残留却全员回到未读。
package service

import (
	"context"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectNoticeVo 对应 SysNoticeMapper.xml <sql id="selectNoticeVo">（全列，notice_content 用 cast(... as char)
// 将 longblob 转字符串便于映射；含 update_by/update_time/remark，异于 dict/post 的 Vo）。
const selectNoticeVo = "select notice_id, notice_title, notice_type, cast(notice_content as char) as notice_content, status, create_by, create_time, update_by, update_time, remark from sys_notice"

// NoticeListFilter 公告列表条件（标题模糊 / 类型精确 / 创建人模糊）。sys_notice 无 del_flag，硬删。
type NoticeListFilter struct {
	NoticeTitle string
	NoticeType  string
	CreateBy    string
}

// buildNoticeListSQL 组装列表查询（<where> 逐条 AND，order by notice_id desc）。
func buildNoticeListSQL(f NoticeListFilter) (string, []interface{}) {
	sql := selectNoticeVo
	conds := make([]string, 0, 3)
	args := make([]interface{}, 0, 3)
	if f.NoticeTitle != "" {
		conds = append(conds, "notice_title like concat('%', ?, '%')")
		args = append(args, f.NoticeTitle)
	}
	if f.NoticeType != "" {
		conds = append(conds, "notice_type = ?")
		args = append(args, f.NoticeType)
	}
	if f.CreateBy != "" {
		conds = append(conds, "create_by like concat('%', ?, '%')")
		args = append(args, f.CreateBy)
	}
	if len(conds) > 0 {
		sql += " where " + joinStrings(conds, " AND ")
	}
	sql += " order by notice_id desc"
	return sql, args
}

func scanNotices(recs gdb.Result) []model.SysNotice {
	out := make([]model.SysNotice, 0, len(recs))
	for _, r := range recs {
		var n model.SysNotice
		if gconv.Struct(r.Map(), &n) == nil {
			out = append(out, n)
		}
	}
	return out
}

// SelectNoticeList 对应 selectNoticeList（不分页）。
func SelectNoticeList(ctx context.Context, f NoticeListFilter) []model.SysNotice {
	sql, args := buildNoticeListSQL(f)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanNotices(recs)
}

// SelectNoticeListPaged 对应 list 端点 startPage + selectNoticeList。
func SelectNoticeListPaged(ctx context.Context, f NoticeListFilter, p Page) ([]model.SysNotice, int64, error) {
	sql, args := buildNoticeListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanNotices(res.Rows), res.Total, nil
}

// SelectNoticeById 对应 selectNoticeById（按 notice_id 定位）。不存在回 nil。
func SelectNoticeById(ctx context.Context, noticeId int64) *model.SysNotice {
	rec, err := g.DB().GetOne(ctx, selectNoticeVo+" where notice_id = ?", noticeId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var n model.SysNotice
	if gconv.Struct(rec.Map(), &n) != nil {
		return nil
	}
	return &n
}

// InsertNotice 对应 insertNotice：动态非空列插入，create_time 由 sysdate() 填充。回写自增 noticeId。
func InsertNotice(ctx context.Context, notice *model.SysNotice, createBy string) int {
	cols := make([]string, 0, 7)
	vals := make([]interface{}, 0, 7)
	add := func(col string, val interface{}) { cols = append(cols, col); vals = append(vals, val) }
	if notice.NoticeTitle != "" {
		add("notice_title", notice.NoticeTitle)
	}
	if notice.NoticeType != "" {
		add("notice_type", notice.NoticeType)
	}
	if notice.NoticeContent != "" {
		add("notice_content", notice.NoticeContent)
	}
	if notice.Status != "" {
		add("status", notice.Status)
	}
	if notice.Remark != "" {
		add("remark", notice.Remark)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	add("create_time", gtime.Now())
	sql := "insert into sys_notice(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	res, err := g.DB().Exec(ctx, sql, vals...)
	if err != nil {
		return 0
	}
	if id, e := res.LastInsertId(); e == nil {
		notice.NoticeId = id
	}
	return affectedOf(res)
}

// UpdateNotice 对应 updateNotice：先回查存在性（把“改了不存在的公告”从静默 rows=0 收敛为明确业务异常），
// 再动态 SET 仅更新非空字段（notice_content 允许清空故由 contentPresent 判定：present 即写入，含空串），update_time 强制刷新。
// 返回 (行数, 错误文案)；文案非空时未执行更新。
func UpdateNotice(ctx context.Context, notice *model.SysNotice, updateBy string, contentPresent bool) (int, string) {
	exist := SelectNoticeById(ctx, notice.NoticeId)
	if exist == nil {
		return 0, "公告不存在或已被删除，noticeId=" + gconv.String(notice.NoticeId)
	}
	sets := make([]string, 0, 6)
	args := make([]interface{}, 0, 6)
	add := func(clause string, val interface{}) { sets = append(sets, clause); args = append(args, val) }
	if notice.NoticeTitle != "" {
		add("notice_title = ?", notice.NoticeTitle)
	}
	if notice.NoticeType != "" {
		add("notice_type = ?", notice.NoticeType)
	}
	if contentPresent {
		add("notice_content = ?", notice.NoticeContent)
	}
	if notice.Status != "" {
		add("status = ?", notice.Status)
	}
	if updateBy != "" {
		add("update_by = ?", updateBy)
	}
	sets = append(sets, "update_time = ?")
	args = append(args, gtime.Now())
	sql := "update sys_notice set " + joinStrings(sets, ", ") + " where notice_id = ?"
	args = append(args, notice.NoticeId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, "修改失败"
	}
	return affectedOf(res), ""
}

// DeleteNoticeByIds 对应 deleteNoticeByIds：@Transactional 内先级联清理 sys_notice_read 已读记录，再删本体。
// 空入参在入口即拒（避免 foreach 生成 IN () 语法错误）。返回 (删除行数, 错误文案)。
func DeleteNoticeByIds(ctx context.Context, noticeIds []int64) (int, string) {
	if len(noticeIds) == 0 {
		return 0, "删除的公告 ID 不能为空"
	}
	args := idsToInterfaces(noticeIds)
	inClause := placeholders(len(noticeIds))
	rows := 0
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, e := tx.Exec("delete from sys_notice_read where notice_id in ("+inClause+")", args...); e != nil {
			return e
		}
		res, e := tx.Exec("delete from sys_notice where notice_id in ("+inClause+")", args...)
		if e != nil {
			return e
		}
		rows = affectedOf(res)
		return nil
	})
	if err != nil {
		return 0, "删除失败"
	}
	return rows, ""
}
