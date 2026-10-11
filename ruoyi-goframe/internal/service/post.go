// Package service —— 岗位只读查询，复刻 SysPostServiceImpl.selectPostAll / selectPostListByUserId，
// 仅供 /system/user getInfo 组装 posts / postIds（岗位管理 CRUD 属后续批次）。
package service

import (
	"context"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectPostVo 对应 SysPostMapper.xml <sql id="selectPostVo">（不含 update_by/update_time，同 resultMap 零值）。
const selectPostVo = "select post_id, post_code, post_name, post_sort, status, create_by, create_time, remark from sys_post"

// SelectPostAll 对应 selectPostAll：全量岗位（用户表单岗位下拉）。
func SelectPostAll(ctx context.Context) []model.SysPost {
	recs, err := g.DB().GetAll(ctx, selectPostVo)
	if err != nil {
		return []model.SysPost{}
	}
	return scanPosts(recs)
}

// SelectPostListByUserId 对应 selectPostListByUserId：取该用户已关联岗位 ID 列表（编辑回显勾选）。
func SelectPostListByUserId(ctx context.Context, userId int64) []int64 {
	recs, err := g.DB().GetAll(ctx,
		"select p.post_id from sys_post p"+
			" left join sys_user_post up on up.post_id = p.post_id"+
			" left join sys_user u on u.user_id = up.user_id"+
			" where u.user_id = ?", userId)
	if err != nil {
		return []int64{}
	}
	out := make([]int64, 0, len(recs))
	for _, rec := range recs {
		id := gconv.Int64(rec.Map()["post_id"])
		if id != 0 {
			out = append(out, id)
		}
	}
	return out
}

func scanPosts(recs gdb.Result) []model.SysPost {
	out := make([]model.SysPost, 0, len(recs))
	for _, r := range recs {
		var p model.SysPost
		if gconv.Struct(r.Map(), &p) == nil {
			out = append(out, p)
		}
	}
	return out
}
