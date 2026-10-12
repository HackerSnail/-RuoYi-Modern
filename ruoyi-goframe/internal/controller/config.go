// Package controller —— /system/config 参数配置 HTTP 处理器，逐条对齐 SysConfigController 的入参/出参/校验顺序/文案。
// 含公开只读端点 configKey/{configKey}（无 @PreAuthorize，走白名单过滤）；已实现 Excel 导出。
package controller

import (
	"io"
	"regexp"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// configTypePattern 复刻 SysConfig.configType 的 @Pattern(regexp="[YN]")：HV 按整串 matches() 判定，
// 故 Go 等价须锚定为 ^[YN]$（Go MatchString 默认是 find 半匹配，不锚会放过 "Yx"）。
var configTypePattern = regexp.MustCompile(`^[YN]$`)

// ConfigList GET /system/config/list（system:config:list）→ TableDataInfo（分页，无 DataScope）。
func ConfigList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.ConfigListFilter{
		ConfigName: r.Get("configName").String(),
		ConfigType: r.Get("configType").String(),
		ConfigKey:  r.Get("configKey").String(),
		BeginTime:  paramRange(r, "beginTime"),
		EndTime:    paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectConfigListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// ConfigExport POST /system/config/export（system:config:export + @Log EXPORT title “参数管理”）：同 list 的过滤入参（参数无 DataScope），
// 过 exportLimit 护栏后写 xlsx（sheet 名“参数数据”）。
func ConfigExport(r *ghttp.Request) {
	ctx := r.Context()
	f := service.ConfigListFilter{
		ConfigName: r.Get("configName").String(),
		ConfigType: r.Get("configType").String(),
		ConfigKey:  r.Get("configKey").String(),
		BeginTime:  paramRange(r, "beginTime"),
		EndTime:    paramRange(r, "endTime"),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectConfigListLimited(ctx, f, max)
	if err != nil {
		api.Send(r, api.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportConfig(w, rows) })
}

// ConfigGetInfo GET /system/config/{configId}（system:config:query）→ data:{config}。
func ConfigGetInfo(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectConfigById(r.Context(), r.Get("configId").Int64())))
}

// ConfigGetKey GET /system/config/configKey/{configKey}（无 @PreAuthorize，仅需登录态）→ data: 键值字符串。
// 非白名单键 → 拒绝（对齐 selectPublicConfigByKey：不回显用户可控 key，仅回通用文案）。
func ConfigGetKey(r *ghttp.Request) {
	value, msg := service.SelectPublicConfigByKey(r.Context(), r.GetRouter("configKey").String())
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.OkData(value))
}

// ConfigAdd POST /system/config（system:config:add）。@Validated → 键名唯一 → insertConfig。
func ConfigAdd(r *ghttp.Request) {
	var config model.SysConfig
	if err := r.Parse(&config); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateConfig(&config); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckConfigKeyUnique(ctx, &config) {
		api.Send(r, api.Err("新增参数'"+config.ConfigName+"'失败，参数键名已存在"))
		return
	}
	api.Send(r, toAjax(service.InsertConfig(ctx, &config, lu.Username)))
}

// ConfigEdit PUT /system/config（system:config:edit）。@Validated → 键名唯一 → 内置参数(库中原值 Y)非超管拒改 → updateConfig。
func ConfigEdit(r *ghttp.Request) {
	var config model.SysConfig
	if err := r.Parse(&config); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateConfig(&config); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckConfigKeyUnique(ctx, &config) {
		api.Send(r, api.Err("修改参数'"+config.ConfigName+"'失败，参数键名已存在"))
		return
	}
	// 内置参数以库中原始 configType 判定（请求体可被篡改），仅超管可改。
	original := service.SelectConfigById(ctx, config.ConfigId)
	admin := lu != nil && lu.User != nil && service.IsAdmin(lu.User.UserId)
	if original != nil && original.ConfigType == consts.Yes && !admin {
		api.Send(r, api.Err("修改参数'"+config.ConfigName+"'失败，内置参数仅超级管理员可修改"))
		return
	}
	api.Send(r, toAjax(service.UpdateConfig(ctx, &config, lu.Username)))
}

// ConfigRemove DELETE /system/config/{configIds}（system:config:remove）。整批预校验（不存在/内置拒绝）后硬删；成功恒回操作成功。
func ConfigRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.Get("configIds").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("参数 ID 非法"))
		return
	}
	if _, msg := service.DeleteConfigByIds(ctx, ids); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.Ok())
}

// ConfigRefreshCache DELETE /system/config/refreshCache（system:config:refresh）。清理 sys_config:* 缓存键后回操作成功。
func ConfigRefreshCache(r *ghttp.Request) {
	service.ResetConfigCache(r.Context())
	api.Send(r, api.Ok())
}

// validateConfig 复刻 SysConfig 的 @Validated 约束（名称/键名/键值 NotBlank+Size，configType @Pattern 整串 Y/N）。
func validateConfig(c *model.SysConfig) []violation {
	var vs []violation
	if c.ConfigName == "" {
		vs = append(vs, violation{"configName", "参数名称不能为空"})
	} else if runeLen(c.ConfigName) > 100 {
		vs = append(vs, violation{"configName", "参数名称不能超过100个字符"})
	}
	if c.ConfigKey == "" {
		vs = append(vs, violation{"configKey", "参数键名长度不能为空"})
	} else if runeLen(c.ConfigKey) > 100 {
		vs = append(vs, violation{"configKey", "参数键名长度不能超过100个字符"})
	}
	if c.ConfigValue == "" {
		vs = append(vs, violation{"configValue", "参数键值不能为空"})
	} else if runeLen(c.ConfigValue) > 500 {
		vs = append(vs, violation{"configValue", "参数键值长度不能超过500个字符"})
	}
	if c.ConfigType != "" && !configTypePattern.MatchString(c.ConfigType) {
		vs = append(vs, violation{"configType", "系统内置参数只能是Y或N"})
	}
	return vs
}
