// Package router —— 路由注册。公开端点(login/captchaImage/logout)无需鉴权；
// getInfo/getRouters 挂 Auth 中间件，语义对齐 SecurityConfig 的 permitAll 白名单 + anyRequest().authenticated()。
package router

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/controller"
	"ruoyi-goframe/internal/middleware"
)

// Register 挂载全部 P0 路由。
func Register(s *ghttp.Server) {
	// 跨域：前端 RuoYi-Vue3 以 Authorization 头携带令牌，CORS 需回显该请求头，gf 默认按请求头动态放行。
	s.Use(ghttp.MiddlewareCORS)

	// 公开端点（对应 permitAll：/login /captchaImage；/logout 由登出处理，尽力删除会话）
	s.BindHandler("GET:/ping", controller.Ping)
	s.BindHandler("GET:/captchaImage", controller.CaptchaImage)
	s.BindHandler("POST:/login", controller.Login)
	s.BindHandler("POST:/register", controller.Register)
	s.BindHandler("POST:/logout", controller.Logout)

	// 受保护端点：必须携带有效令牌
	s.Group("/", func(group *ghttp.RouterGroup) {
		group.Middleware(middleware.Auth)
		group.GET("/getInfo", controller.GetInfo)
		group.GET("/getRouters", controller.GetRouters)
	})

	// P1a 菜单/部门。用根分组 + 完整路径注册（避免子分组内 g.POST("") 空 pattern 无法映射到裸路径 /system/menu）。
	// 各端点对齐 @PreAuthorize；treeselect 仅需登录态无 permi。GF 路由只接受单个 handler，权限用 Permi 与控制器组合。
	s.Group("/", func(g *ghttp.RouterGroup) {
		g.Middleware(middleware.Auth)
		// 菜单 /system/menu
		g.GET("/system/menu/list", middleware.Permi("system:menu:list", controller.MenuList))
		g.GET("/system/menu/treeselect", controller.MenuTreeselect)
		g.GET("/system/menu/roleMenuTreeselect/{roleId}", middleware.Permi("system:role:query", controller.RoleMenuTreeselect))
		g.POST("/system/menu", middleware.Permi("system:menu:add", middleware.Log(middleware.OpLog("菜单管理", consts.BtInsert, "controller.MenuAdd"), controller.MenuAdd)))
		g.PUT("/system/menu/updateSort", middleware.Permi("system:menu:edit", middleware.Log(middleware.OpLog("保存菜单排序", consts.BtUpdate, "controller.MenuUpdateSort"), controller.MenuUpdateSort)))
		g.PUT("/system/menu", middleware.Permi("system:menu:edit", middleware.Log(middleware.OpLog("菜单管理", consts.BtUpdate, "controller.MenuEdit"), controller.MenuEdit)))
		g.GET("/system/menu/{menuId}", middleware.Permi("system:menu:query", controller.MenuGetInfo))
		g.DELETE("/system/menu/{menuId}", middleware.Permi("system:menu:remove", middleware.Log(middleware.OpLog("菜单管理", consts.BtDelete, "controller.MenuRemove"), controller.MenuRemove)))
		// 部门 /system/dept
		g.GET("/system/dept/list", middleware.Permi("system:dept:list", controller.DeptList))
		g.GET("/system/dept/list/exclude/{deptId}", middleware.Permi("system:dept:list", controller.DeptExclude))
		g.POST("/system/dept", middleware.Permi("system:dept:add", middleware.Log(middleware.OpLog("部门管理", consts.BtInsert, "controller.DeptAdd"), controller.DeptAdd)))
		g.PUT("/system/dept/updateSort", middleware.Permi("system:dept:edit", middleware.Log(middleware.OpLog("保存部门排序", consts.BtUpdate, "controller.DeptUpdateSort"), controller.DeptUpdateSort)))
		g.PUT("/system/dept", middleware.Permi("system:dept:edit", middleware.Log(middleware.OpLog("部门管理", consts.BtUpdate, "controller.DeptEdit"), controller.DeptEdit)))
		g.GET("/system/dept/{deptId}", middleware.Permi("system:dept:query", controller.DeptGetInfo))
		g.DELETE("/system/dept/{deptId}", middleware.Permi("system:dept:remove", middleware.Log(middleware.OpLog("部门管理", consts.BtDelete, "controller.DeptRemove"), controller.DeptRemove)))
		// 角色 /system/role（CRUD + optionselect + deptTree；authUser 系列随后注册）
		g.GET("/system/role/list", middleware.Permi("system:role:list", controller.RoleList))
		g.POST("/system/role/export", middleware.Permi("system:role:export", middleware.Log(middleware.OpLog("角色管理", consts.BtExport, "controller.RoleExport"), controller.RoleExport)))
		g.GET("/system/role/optionselect", middleware.Permi("system:role:query", controller.RoleOptionSelect))
		g.GET("/system/role/deptTree/{roleId}", middleware.Permi("system:role:query", controller.RoleDeptTree))
		g.POST("/system/role", middleware.Permi("system:role:add", middleware.Log(middleware.OpLog("角色管理", consts.BtInsert, "controller.RoleAdd"), controller.RoleAdd)))
		g.PUT("/system/role/dataScope", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtUpdate, "controller.RoleDataScope"), controller.RoleDataScope)))
		g.PUT("/system/role/changeStatus", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtUpdate, "controller.RoleChangeStatus"), controller.RoleChangeStatus)))
		g.PUT("/system/role", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtUpdate, "controller.RoleEdit"), controller.RoleEdit)))
		g.GET("/system/role/{roleId}", middleware.Permi("system:role:query", controller.RoleGetInfo))
		g.DELETE("/system/role/{roleIds}", middleware.Permi("system:role:remove", middleware.Log(middleware.OpLog("角色管理", consts.BtDelete, "controller.RoleRemove"), controller.RoleRemove)))
		// 角色授权用户 /system/role/authUser
		g.GET("/system/role/authUser/allocatedList", middleware.Permi("system:role:list", controller.AllocatedList))
		g.GET("/system/role/authUser/unallocatedList", middleware.Permi("system:role:list", controller.UnallocatedList))
		g.PUT("/system/role/authUser/cancel", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtGrant, "controller.CancelAuthUser"), controller.CancelAuthUser)))
		g.PUT("/system/role/authUser/cancelAll", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtGrant, "controller.CancelAuthUserAll"), controller.CancelAuthUserAll)))
		g.PUT("/system/role/authUser/selectAll", middleware.Permi("system:role:edit", middleware.Log(middleware.OpLog("角色管理", consts.BtGrant, "controller.SelectAuthUserAll"), controller.SelectAuthUserAll)))
		// 用户 /system/user（静态路径先于 {userId} 动态路径注册，利用 GF 字面量优先于通配的匹配）
		g.GET("/system/user/list", middleware.Permi("system:user:list", controller.UserList))
		g.POST("/system/user/export", middleware.Permi("system:user:export", middleware.Log(middleware.OpLog("用户管理", consts.BtExport, "controller.UserExport"), controller.UserExport)))
		// 导入（importData 对齐 @PreAuthorize system:user:import + @Log IMPORT；importTemplate 无 @PreAuthorize/无 @Log，仅登录态裸注册）
		g.POST("/system/user/importData", middleware.Permi("system:user:import", middleware.Log(middleware.OpLog("用户管理", consts.BtImport, "controller.UserImportData"), controller.UserImportData)))
		g.POST("/system/user/importTemplate", controller.UserImportTemplate)
		g.GET("/system/user/deptTree", middleware.Permi("system:user:list", controller.UserDeptTree))
		g.GET("/system/user/authRole/{userId}", middleware.Permi("system:user:query", controller.UserAuthRole))
		g.GET("/system/user/", middleware.Permi("system:user:query", controller.UserGetInfo))
		g.POST("/system/user", middleware.Permi("system:user:add", middleware.Log(middleware.OpLog("用户管理", consts.BtInsert, "controller.UserAdd"), controller.UserAdd)))
		g.PUT("/system/user/resetPwd", middleware.Permi("system:user:resetPwd", middleware.Log(middleware.OpLog("用户管理", consts.BtUpdate, "controller.UserResetPwd"), controller.UserResetPwd)))
		g.PUT("/system/user/changeStatus", middleware.Permi("system:user:edit", middleware.Log(middleware.OpLog("用户管理", consts.BtUpdate, "controller.UserChangeStatus"), controller.UserChangeStatus)))
		g.PUT("/system/user/authRole", middleware.Permi("system:user:edit", middleware.Log(middleware.OpLog("用户管理", consts.BtGrant, "controller.UserInsertAuthRole"), controller.UserInsertAuthRole)))
		g.PUT("/system/user", middleware.Permi("system:user:edit", middleware.Log(middleware.OpLog("用户管理", consts.BtUpdate, "controller.UserEdit"), controller.UserEdit)))
		g.GET("/system/user/{userId}", middleware.Permi("system:user:query", controller.UserGetInfo))
		g.DELETE("/system/user/{userIds}", middleware.Permi("system:user:remove", middleware.Log(middleware.OpLog("用户管理", consts.BtDelete, "controller.UserRemove"), controller.UserRemove)))
		// 个人中心 /system/user/profile（对齐 SysProfileController：均无 @PreAuthorize，仅需登录）。
		// 静态段 profile/updatePwd/avatar 先于 /system/user/{userId} 动态段注册（GF 字面量优先于通配）。
		g.GET("/system/user/profile", controller.Profile)
		g.PUT("/system/user/profile/updatePwd", middleware.Log(middleware.OpLog("个人信息", consts.BtUpdate, "controller.UpdatePwd"), controller.UpdatePwd))
		g.PUT("/system/user/profile", middleware.Log(middleware.OpLog("个人信息", consts.BtUpdate, "controller.UpdateProfile"), controller.UpdateProfile))
		g.POST("/system/user/profile/avatar", middleware.Log(middleware.OpLog("用户头像", consts.BtUpdate, "controller.Avatar"), controller.Avatar))
		// 岗位 /system/post（optionselect/list 静态先于 {postId} 动态；Java optionselect 无 @PreAuthorize，仅挂 Auth）
		g.GET("/system/post/list", middleware.Permi("system:post:list", controller.PostList))
		g.POST("/system/post/export", middleware.Permi("system:post:export", middleware.Log(middleware.OpLog("岗位管理", consts.BtExport, "controller.PostExport"), controller.PostExport)))
		g.GET("/system/post/optionselect", controller.PostOptionSelect)
		g.POST("/system/post", middleware.Permi("system:post:add", middleware.Log(middleware.OpLog("岗位管理", consts.BtInsert, "controller.PostAdd"), controller.PostAdd)))
		g.PUT("/system/post", middleware.Permi("system:post:edit", middleware.Log(middleware.OpLog("岗位管理", consts.BtUpdate, "controller.PostEdit"), controller.PostEdit)))
		g.GET("/system/post/{postId}", middleware.Permi("system:post:query", controller.PostGetInfo))
		g.DELETE("/system/post/{postIds}", middleware.Permi("system:post:remove", middleware.Log(middleware.OpLog("岗位管理", consts.BtDelete, "controller.PostRemove"), controller.PostRemove)))
		// 参数 /system/config（configKey 公开只读无 permi；refreshCache 静态先于 {configIds} 动态）
		g.GET("/system/config/list", middleware.Permi("system:config:list", controller.ConfigList))
		g.POST("/system/config/export", middleware.Permi("system:config:export", middleware.Log(middleware.OpLog("参数管理", consts.BtExport, "controller.ConfigExport"), controller.ConfigExport)))
		g.GET("/system/config/configKey/{configKey}", controller.ConfigGetKey)
		g.POST("/system/config", middleware.Permi("system:config:add", middleware.Log(middleware.OpLog("参数管理", consts.BtInsert, "controller.ConfigAdd"), controller.ConfigAdd)))
		g.PUT("/system/config", middleware.Permi("system:config:edit", middleware.Log(middleware.OpLog("参数管理", consts.BtUpdate, "controller.ConfigEdit"), controller.ConfigEdit)))
		g.DELETE("/system/config/refreshCache", middleware.Permi("system:config:refresh", middleware.Log(middleware.OpLog("参数管理", consts.BtClean, "controller.ConfigRefreshCache"), controller.ConfigRefreshCache)))
		g.GET("/system/config/{configId}", middleware.Permi("system:config:query", controller.ConfigGetInfo))
		g.DELETE("/system/config/{configIds}", middleware.Permi("system:config:remove", middleware.Log(middleware.OpLog("参数管理", consts.BtDelete, "controller.ConfigRemove"), controller.ConfigRemove)))
		// 字典类型 /system/dict/type（optionselect/refreshCache 静态先于 {dictId}/{dictIds} 动态；optionselect 无 permi）
		g.GET("/system/dict/type/list", middleware.Permi("system:dict:list", controller.DictTypeList))
		g.POST("/system/dict/type/export", middleware.Permi("system:dict:export", middleware.Log(middleware.OpLog("字典类型", consts.BtExport, "controller.DictTypeExport"), controller.DictTypeExport)))
		g.GET("/system/dict/type/optionselect", controller.DictTypeOptionSelect)
		g.POST("/system/dict/type", middleware.Permi("system:dict:add", middleware.Log(middleware.OpLog("字典类型", consts.BtInsert, "controller.DictTypeAdd"), controller.DictTypeAdd)))
		g.PUT("/system/dict/type", middleware.Permi("system:dict:edit", middleware.Log(middleware.OpLog("字典类型", consts.BtUpdate, "controller.DictTypeEdit"), controller.DictTypeEdit)))
		g.DELETE("/system/dict/type/refreshCache", middleware.Permi("system:dict:refresh", middleware.Log(middleware.OpLog("字典类型", consts.BtClean, "controller.DictTypeRefreshCache"), controller.DictTypeRefreshCache)))
		g.GET("/system/dict/type/{dictId}", middleware.Permi("system:dict:query", controller.DictTypeGetInfo))
		g.DELETE("/system/dict/type/{dictIds}", middleware.Permi("system:dict:remove", middleware.Log(middleware.OpLog("字典类型", consts.BtDelete, "controller.DictTypeRemove"), controller.DictTypeRemove)))
		// 字典数据 /system/dict/data（list/type 静态先于 {dictCode}/{dictCodes} 动态；data/type/{dictType} 无 permi，走敏感黑名单）
		g.GET("/system/dict/data/list", middleware.Permi("system:dict:list", controller.DictDataList))
		g.POST("/system/dict/data/export", middleware.Permi("system:dict:export", middleware.Log(middleware.OpLog("字典数据", consts.BtExport, "controller.DictDataExport"), controller.DictDataExport)))
		g.GET("/system/dict/data/type/{dictType}", controller.DictDataType)
		g.POST("/system/dict/data", middleware.Permi("system:dict:add", middleware.Log(middleware.OpLog("字典数据", consts.BtInsert, "controller.DictDataAdd"), controller.DictDataAdd)))
		g.PUT("/system/dict/data", middleware.Permi("system:dict:edit", middleware.Log(middleware.OpLog("字典数据", consts.BtUpdate, "controller.DictDataEdit"), controller.DictDataEdit)))
		g.GET("/system/dict/data/{dictCode}", middleware.Permi("system:dict:query", controller.DictDataGetInfo))
		g.DELETE("/system/dict/data/{dictCodes}", middleware.Permi("system:dict:remove", middleware.Log(middleware.OpLog("字典数据", consts.BtDelete, "controller.DictDataRemove"), controller.DictDataRemove)))
		// 通知公告 /system/notice（C1 核心 CRUD；getInfo 无 permi 仅需登录；list 静态先于 {noticeId}/{noticeIds} 动态）
		g.GET("/system/notice/list", middleware.Permi("system:notice:list", controller.NoticeList))
		g.POST("/system/notice", middleware.Permi("system:notice:add", middleware.Log(middleware.OpLog("通知公告", consts.BtInsert, "controller.NoticeAdd"), controller.NoticeAdd)))
		g.PUT("/system/notice", middleware.Permi("system:notice:edit", middleware.Log(middleware.OpLog("通知公告", consts.BtUpdate, "controller.NoticeEdit"), controller.NoticeEdit)))
		// C2 已读追踪：listTop/markRead/markReadAll 仅需登录（无 permi）；repush 走 edit；read/unreadUsers 走 list。静态段先于 {noticeId} 动态。
		g.GET("/system/notice/listTop", controller.NoticeListTop)
		g.POST("/system/notice/markRead", controller.NoticeMarkRead)
		g.POST("/system/notice/markReadAll", controller.NoticeMarkReadAll)
		g.GET("/system/notice/readUsers/list", middleware.Permi("system:notice:list", controller.NoticeReadUsersList))
		g.GET("/system/notice/unreadUsers/list", middleware.Permi("system:notice:list", controller.NoticeUnreadUsersList))
		g.PUT("/system/notice/repush/{noticeId}", middleware.Permi("system:notice:edit", middleware.Log(middleware.OpLog("公告重新推送", consts.BtUpdate, "controller.NoticeRepush"), controller.NoticeRepush)))
		g.GET("/system/notice/{noticeId}", controller.NoticeGetInfo)
		g.DELETE("/system/notice/{noticeIds}", middleware.Permi("system:notice:remove", middleware.Log(middleware.OpLog("通知公告", consts.BtDelete, "controller.NoticeRemove"), controller.NoticeRemove)))
		// 通用文件 /common（对齐 CommonController：均无 @PreAuthorize，仅需登录；下载类接口 Java 为静默 200 语义）
		g.GET("/common/download/resource", controller.ResourceDownload)
		g.GET("/common/download", controller.FileDownload)
		g.POST("/common/upload", controller.FileUpload)
		g.POST("/common/uploads", controller.FileUploads)
		// 上传目录静态取图 /profile/**：本仓 SecurityConfig 已取消匿名放行，须带令牌（挂 Auth、无 permi）。
		g.GET("/profile/*", controller.ProfileResource)
		// 在线用户监控 /monitor/online（对齐 SysUserOnlineController：两接口均带 @PreAuthorize；数据源 Redis 会话，无 DB）
		g.GET("/monitor/online/list", middleware.Permi("monitor:online:list", controller.OnlineList))
		g.DELETE("/monitor/online/{tokenId}", middleware.Permi("monitor:online:forceLogout", middleware.Log(middleware.OpLog("在线用户", consts.BtForce, "controller.OnlineForceLogout"), controller.OnlineForceLogout)))
		// 操作日志监控 /monitor/operlog（对齐 SysOperlogController：list/remove/clean；clean 独立权限 monitor:operlog:clean；export 顺延）
		g.GET("/monitor/operlog/list", middleware.Permi("monitor:operlog:list", controller.OperLogList))
		g.POST("/monitor/operlog/export", middleware.Permi("monitor:operlog:export", middleware.Log(middleware.OpLog("操作日志", consts.BtExport, "controller.OperLogExport"), controller.OperLogExport)))
		g.DELETE("/monitor/operlog/clean", middleware.Permi("monitor:operlog:clean", middleware.Log(middleware.OpLog("操作日志", consts.BtClean, "controller.OperLogClean"), controller.OperLogClean)))
		g.DELETE("/monitor/operlog/{operIds}", middleware.Permi("monitor:operlog:remove", middleware.Log(middleware.OpLog("操作日志", consts.BtDelete, "controller.OperLogRemove"), controller.OperLogRemove)))
		// 登录日志监控 /monitor/logininfor（对齐 SysLogininforController：list/remove/clean/unlock；clean 复用 remove 权限；export 顺延）
		g.GET("/monitor/logininfor/list", middleware.Permi("monitor:logininfor:list", controller.LogininforList))
		g.POST("/monitor/logininfor/export", middleware.Permi("monitor:logininfor:export", middleware.Log(middleware.OpLog("登录日志", consts.BtExport, "controller.LogininforExport"), controller.LogininforExport)))
		g.GET("/monitor/logininfor/unlock/{userName}", middleware.Permi("monitor:logininfor:unlock", middleware.Log(middleware.OpLog("账户解锁", consts.BtOther, "controller.LogininforUnlock"), controller.LogininforUnlock)))
		g.DELETE("/monitor/logininfor/clean", middleware.Permi("monitor:logininfor:remove", middleware.Log(middleware.OpLog("登录日志", consts.BtClean, "controller.LogininforClean"), controller.LogininforClean)))
		g.DELETE("/monitor/logininfor/{infoIds}", middleware.Permi("monitor:logininfor:remove", middleware.Log(middleware.OpLog("登录日志", consts.BtDelete, "controller.LogininforRemove"), controller.LogininforRemove)))
		// 定时任务调度 /monitor/job（对齐 SysJobController：list/export/getInfo/add/edit/changeStatus/run/remove；@Log title=定时任务）。静态 list/export/changeStatus/run 先于动态 {jobId}/{jobIds}。
		g.GET("/monitor/job/list", middleware.Permi("monitor:job:list", controller.JobList))
		g.POST("/monitor/job/export", middleware.Permi("monitor:job:export", middleware.Log(middleware.OpLog("定时任务", consts.BtExport, "controller.JobExport"), controller.JobExport)))
		g.PUT("/monitor/job/changeStatus", middleware.Permi("monitor:job:changeStatus", middleware.Log(middleware.OpLog("定时任务", consts.BtUpdate, "controller.JobChangeStatus"), controller.JobChangeStatus)))
		g.PUT("/monitor/job/run", middleware.Permi("monitor:job:changeStatus", middleware.Log(middleware.OpLog("定时任务", consts.BtUpdate, "controller.JobRun"), controller.JobRun)))
		g.POST("/monitor/job", middleware.Permi("monitor:job:add", middleware.Log(middleware.OpLog("定时任务", consts.BtInsert, "controller.JobAdd"), controller.JobAdd)))
		g.PUT("/monitor/job", middleware.Permi("monitor:job:edit", middleware.Log(middleware.OpLog("定时任务", consts.BtUpdate, "controller.JobEdit"), controller.JobEdit)))
		g.GET("/monitor/job/{jobId}", middleware.Permi("monitor:job:query", controller.JobGetInfo))
		g.DELETE("/monitor/job/{jobIds}", middleware.Permi("monitor:job:remove", middleware.Log(middleware.OpLog("定时任务", consts.BtDelete, "controller.JobRemove"), controller.JobRemove)))
		// 定时任务调度日志 /monitor/jobLog（对齐 SysJobController：list/export/getInfo/remove/clean；权限均复用 monitor:job:*，clean 复用 remove 且无二次确认门，Java 原样）。静态 clean 先于 {jobLogIds} 动态。
		g.GET("/monitor/jobLog/list", middleware.Permi("monitor:job:list", controller.JobLogList))
		g.POST("/monitor/jobLog/export", middleware.Permi("monitor:job:export", middleware.Log(middleware.OpLog("任务调度日志", consts.BtExport, "controller.JobLogExport"), controller.JobLogExport)))
		g.DELETE("/monitor/jobLog/clean", middleware.Permi("monitor:job:remove", middleware.Log(middleware.OpLog("调度日志", consts.BtClean, "controller.JobLogClean"), controller.JobLogClean)))
		g.GET("/monitor/jobLog/{jobLogId}", middleware.Permi("monitor:job:query", controller.JobLogGetInfo))
		g.DELETE("/monitor/jobLog/{jobLogIds}", middleware.Permi("monitor:job:remove", middleware.Log(middleware.OpLog("定时任务调度日志", consts.BtDelete, "controller.JobLogRemove"), controller.JobLogRemove)))
		// 缓存监控 /monitor/cache（对齐 CacheController：全部 @PreAuthorize monitor:cache:list；数据源 Redis 管理命令，不走 DB）
		g.GET("/monitor/cache", middleware.Permi("monitor:cache:list", controller.CacheInfo))
		g.GET("/monitor/cache/getNames", middleware.Permi("monitor:cache:list", controller.CacheNames))
		g.GET("/monitor/cache/getKeys/{cacheName}", middleware.Permi("monitor:cache:list", controller.CacheKeys))
		g.GET("/monitor/cache/getValue/{cacheName}/{cacheKey}", middleware.Permi("monitor:cache:list", controller.CacheValue))
		g.DELETE("/monitor/cache/clearCacheName/{cacheName}", middleware.Permi("monitor:cache:list", controller.ClearCacheName))
		g.DELETE("/monitor/cache/clearCacheKey/{cacheKey}", middleware.Permi("monitor:cache:list", controller.ClearCacheKey))
		g.DELETE("/monitor/cache/clearCacheAll", middleware.Permi("monitor:cache:list", controller.ClearCacheAll))
		// 服务器监控 /monitor/server（对齐 ServerController.getInfo：单接口 GET，权限 monitor:server:list；返回后台预采集最近快照）
		g.GET("/monitor/server", middleware.Permi("monitor:server:list", controller.ServerGetInfo))
		// 代码生成 /tool/gen（16-A 元数据 CRUD + 16-B② 代码预览，对齐 GenController；权限/[@Log] 逐字收口）。
		// 静态段 list/db/column/importTable/createTable/synchDb/preview 先于动态 {tableId} 注册（GF 字面量优先于通配）。
		// download/genCode/batchGenCode 依赖落盘/打包，随 16-C 落地。
		g.GET("/tool/gen/list", middleware.Permi("tool:gen:list", controller.GenList))
		g.GET("/tool/gen/db/list", middleware.Permi("tool:gen:list", controller.GenDataList))
		g.GET("/tool/gen/column/{tableId}", middleware.Permi("tool:gen:list", controller.GenColumnList))
		g.POST("/tool/gen/importTable", middleware.Permi("tool:gen:import", middleware.Log(middleware.OpLog("代码生成", consts.BtImport, "controller.ImportTable"), controller.ImportTable)))
		g.POST("/tool/gen/createTable", middleware.Permi("tool:gen:create", middleware.Log(middleware.OpLog("创建表", consts.BtOther, "controller.CreateTable"), controller.CreateTable)))
		g.PUT("/tool/gen", middleware.Permi("tool:gen:edit", middleware.Log(middleware.OpLog("代码生成", consts.BtUpdate, "controller.GenEdit"), controller.GenEdit)))
		g.GET("/tool/gen/synchDb/{tableName}", middleware.Permi("tool:gen:edit", middleware.Log(middleware.OpLog("代码生成", consts.BtUpdate, "controller.GenSynchDb"), controller.GenSynchDb)))
		g.GET("/tool/gen/preview/{tableId}", middleware.Permi("tool:gen:preview", controller.GenPreview))
		g.DELETE("/tool/gen/{tableIds}", middleware.Permi("tool:gen:remove", middleware.Log(middleware.OpLog("代码生成", consts.BtDelete, "controller.GenRemove"), controller.GenRemove)))
		g.GET("/tool/gen/{tableId}", middleware.Permi("tool:gen:query", controller.GenGetInfo))
		// 回收站 /system/recyclebin（对齐 SysRecycleBinController：list/restore/purge 以 {type} 分派）。
		// 权限须据 {type} 运行时派生、而 Permi 需编译期常量，故不走 Permi，由 handler 内 parseType→程序化判权限（白名单先于权限串）。
		g.GET("/system/recyclebin/{type}/list", controller.RecycleBinList)
		g.PUT("/system/recyclebin/{type}/restore", middleware.Log(middleware.OpLog("回收站", consts.BtUpdate, "controller.RecycleBinRestore"), controller.RecycleBinRestore))
		g.DELETE("/system/recyclebin/{type}/purge", middleware.Log(middleware.OpLog("回收站", consts.BtDelete, "controller.RecycleBinPurge"), controller.RecycleBinPurge))
		// 屏幕解锁 /unlockscreen（对齐 SysIndexController：仅登录态、无 @PreAuthorize；@Log(isSaveRequestData=false)，
		// 故不走 OpLog() 便捷构造（其默认 SaveRequest=true），显式内联 meta 关掉 SaveRequest 以免把明文密码写进 sys_oper_log）。
		g.POST("/unlockscreen", middleware.Log(middleware.OperLogMeta{Title: "屏幕解锁", BusinessType: consts.BtOther, OperatorType: consts.OpTypeManage, Method: "controller.UnlockScreen", SaveRequest: false, SaveResponse: true}, controller.UnlockScreen))
	})
}
