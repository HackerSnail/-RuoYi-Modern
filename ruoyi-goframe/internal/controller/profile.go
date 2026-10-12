// Package controller —— 个人中心 /system/user/profile。对齐 SysProfileController 的 4 个接口
// （均无 @PreAuthorize，仅挂 Auth）：查个人信息、改用户、重置密码、上传头像；并承接本仓改造后的
// /profile/** 鉴权取图（SecurityConfig 已移除匿名放行，故取图须带令牌，见 ProfileResource）。
package controller

import (
	"mime"
	"net/http"
	"path/filepath"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// avatarMaxSize 头像大小上限 2MB（对齐 SysProfileController.AVATAR_MAX_SIZE），比全局 multipart 更严。
const avatarMaxSize = 2 * 1024 * 1024

// profileUpdateBody PUT /system/user/profile 请求体：四字段均为指针以区分"未提交(nil)"与"提交空串"。
// 复刻 Java @RequestBody SysUser 后 setXxx 到缓存快照、再由 updateUser 动态列 mapper 依 `!= null` 决定落列的语义。
type profileUpdateBody struct {
	NickName    *string `json:"nickName"`
	Email       *string `json:"email"`
	Phonenumber *string `json:"phonenumber"`
	Sex         *string `json:"sex"`
}

// Profile GET /system/user/profile → {code,msg,data:当前用户快照, roleGroup, postGroup}。
func Profile(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil || lu.User == nil {
		api.Send(r, api.Err("获取用户信息异常，请联系管理员"))
		return
	}
	ctx := r.Context()
	m := api.OkData(lu.User)
	m["roleGroup"] = service.SelectUserRoleGroup(ctx, lu.Username)
	m["postGroup"] = service.SelectUserPostGroup(ctx, lu.Username)
	api.Send(r, m)
}

// UpdateProfile PUT /system/user/profile：以请求四字段覆盖会话快照 → 手机/邮箱唯一性拦截 → 落库 → 刷新缓存会话。
func UpdateProfile(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil || lu.User == nil {
		api.Send(r, api.Err("修改个人信息异常，请联系管理员"))
		return
	}
	var b profileUpdateBody
	if err := r.Parse(&b); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	userId := lu.User.UserId

	// 唯一性：仅当提交值非空才校验（对齐 StringUtils.isNotEmpty 前置）。命中他人占用即拦截。
	if b.Phonenumber != nil && *b.Phonenumber != "" {
		if !service.CheckPhoneUnique(ctx, &model.SysUser{UserId: userId, Phonenumber: *b.Phonenumber}) {
			api.Send(r, api.Err("修改用户'"+lu.Username+"'失败，手机号码已存在"))
			return
		}
	}
	if b.Email != nil && *b.Email != "" {
		if !service.CheckEmailUnique(ctx, &model.SysUser{UserId: userId, Email: *b.Email}) {
			api.Send(r, api.Err("修改用户'"+lu.Username+"'失败，邮箱账号已存在"))
			return
		}
	}

	if service.UpdateUserProfile(ctx, userId, b.NickName, b.Email, b.Phonenumber, b.Sex) > 0 {
		// 更新缓存用户信息（对齐 tokenService.setLoginUser）：仅覆盖提交了的字段，保留其余快照。
		if b.NickName != nil {
			lu.User.NickName = *b.NickName
		}
		if b.Email != nil {
			lu.User.Email = *b.Email
		}
		if b.Phonenumber != nil {
			lu.User.Phonenumber = *b.Phonenumber
		}
		if b.Sex != nil {
			lu.User.Sex = *b.Sex
		}
		service.RefreshSession(ctx, lu)
		api.Send(r, api.Ok())
		return
	}
	api.Send(r, api.Err("修改个人信息异常，请联系管理员"))
}

// updatePwdBody PUT /system/user/profile/updatePwd 请求体。
type updatePwdBody struct {
	OldPassword string `json:"oldPassword"`
	NewPassword string `json:"newPassword"`
}

// UpdatePwd PUT /system/user/profile/updatePwd：BCrypt 校验旧密 → 新密≠旧密 → 改密 → 强制下线全部会话。
func UpdatePwd(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil {
		api.Send(r, api.Err("修改密码异常，请联系管理员"))
		return
	}
	var b updatePwdBody
	if err := r.Parse(&b); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	userId := lu.UserId
	if userId == 0 && lu.User != nil {
		userId = lu.User.UserId
	}
	stored := service.SelectUserPasswordById(ctx, userId)
	// 先校验旧密码（BCrypt），再拦新旧相同（顺序对齐 Java）。
	if !service.MatchesPassword(b.OldPassword, stored) {
		api.Send(r, api.Err("修改密码失败，旧密码错误"))
		return
	}
	if service.MatchesPassword(b.NewPassword, stored) {
		api.Send(r, api.Err("新密码不能与旧密码相同"))
		return
	}
	hash := service.EncryptPassword(b.NewPassword)
	if rows, _ := service.ResetPwd(ctx, &model.SysUser{UserId: userId, Password: hash}); rows > 0 {
		// 改密成功后强制下线该用户全部在线会话（含当前）：避免旧密码签发的 token 仍可用。
		service.DelLoginUsers(ctx, userId)
		api.Send(r, api.OkMsg("密码修改成功，请使用新密码重新登录"))
		return
	}
	api.Send(r, api.Err("修改密码异常，请联系管理员"))
}

// Avatar POST /system/user/profile/avatar（multipart 参数名 avatarfile）：非空 + ≤2MB → UUID 命名落盘 → 回写头像 → 删旧文件 → 刷新缓存。
func Avatar(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil || lu.User == nil {
		api.Send(r, api.Err("上传图片异常，请联系管理员"))
		return
	}
	files := r.GetMultipartFiles("avatarfile")
	if len(files) == 0 || files[0].Size == 0 {
		// 无文件/空文件（对齐 Java file.isEmpty() 分支）
		api.Send(r, api.Err("上传图片异常，请联系管理员"))
		return
	}
	fh := files[0]
	if fh.Size > avatarMaxSize {
		api.Send(r, api.Err("头像图片不能超过 2MB，请压缩后重试"))
		return
	}
	ctx := r.Context()
	avatarUrl, _, err := service.UploadAvatar(ctx, service.GetAvatarPath(ctx), fh)
	if err != nil {
		g.Log().Errorf(ctx, "头像上传失败: %v", err)
		api.Send(r, api.Err(err.Error()))
		return
	}
	userId := lu.User.UserId
	if !service.UpdateUserAvatar(ctx, userId, avatarUrl) {
		api.Send(r, api.Err("上传图片异常，请联系管理员"))
		return
	}
	// 新头像保存成功后删除磁盘上的旧头像，避免孤儿文件（对齐 FileUtils.deleteFile(getProfile()+stripPrefix(old))）。
	if old := lu.User.Avatar; old != "" {
		if local, ok := service.ResolveProfileLocalPath(ctx, old); ok {
			service.DeleteFileIfExists(local)
		}
	}
	m := api.Ok()
	m["imgUrl"] = avatarUrl
	// 更新缓存用户头像并回写会话。
	lu.User.Avatar = avatarUrl
	service.RefreshSession(ctx, lu)
	api.Send(r, m)
}

// ProfileResource GET /profile/**：本仓 SecurityConfig 将上传目录改为需鉴权（不再匿名放行），
// 故此处挂 Auth 后按 /profile 前缀剥出相对路径 → canonical 基目录锚定（防穿越）→ 存在则按扩展名
// 回 Content-Type 流式输出，缺失/越界回 404（对齐 Spring ResourceHttpRequestHandler 语义）。
func ProfileResource(r *ghttp.Request) {
	ctx := r.Context()
	local, ok := service.ResolveProfileLocalPath(ctx, r.URL.Path)
	if !ok {
		// 无 /profile 前缀或越出根目录（含 .. 穿越）：对齐 Spring 静态处理器——不可解析一律 404。
		r.Response.ClearBuffer()
		r.Response.Status = http.StatusNotFound
		return
	}
	// 先探一次存在性再落头，避免对 404 也写内容类型；StreamProfileFile 内部会 stat。
	r.Response.ClearBuffer()
	r.Response.Header().Set("Content-Type", contentTypeOf(local))
	if found, err := service.StreamProfileFile(r.Response.BufferWriter, local); err != nil || !found {
		if err != nil {
			g.Log().Errorf(ctx, "读取上传资源失败 %s: %v", local, err)
		}
		r.Response.ClearBuffer()
		r.Response.Status = http.StatusNotFound
		return
	}
}

// contentTypeOf 依扩展名给出 MIME，未知回 application/octet-stream。
func contentTypeOf(localPath string) string {
	if ct := mime.TypeByExtension(filepath.Ext(localPath)); ct != "" {
		return ct
	}
	return "application/octet-stream"
}
