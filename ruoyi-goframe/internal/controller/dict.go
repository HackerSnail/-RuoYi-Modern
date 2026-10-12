// Package controller —— /system/dict/type 与 /system/dict/data HTTP 处理器，逐条对齐 SysDictTypeController + SysDictDataController。
// 含公开只读端点 data/type/{dictType}（无 @PreAuthorize，走敏感字典黑名单）；Excel 导出属后续批次。
package controller

import (
	"context"
	"regexp"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// dictTypePattern 复刻 SysDictType.dictType 的 @Pattern(regexp="^[a-z][a-z0-9_]*$")（整串锚定，Go 已带 ^ $）。
var dictTypePattern = regexp.MustCompile(`^[a-z][a-z0-9_]*$`)

// ---------------- 字典类型 /system/dict/type ----------------

// DictTypeList GET /system/dict/type/list（system:dict:list）→ TableDataInfo（分页，无 DataScope）。
func DictTypeList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.DictTypeListFilter{
		DictName:  r.Get("dictName").String(),
		DictType:  r.Get("dictType").String(),
		Status:    r.Get("status").String(),
		BeginTime: paramRange(r, "beginTime"),
		EndTime:   paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectDictTypeListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// DictTypeGetInfo GET /system/dict/type/{dictId}（system:dict:query）→ data:{dictType}。
func DictTypeGetInfo(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectDictTypeById(r.Context(), r.Get("dictId").Int64())))
}

// DictTypeOptionSelect GET /system/dict/type/optionselect（Java 无 @PreAuthorize，仅需登录态）→ data:[SysDictType...]。
func DictTypeOptionSelect(r *ghttp.Request) {
	list := service.SelectDictTypeAll(r.Context())
	if list == nil {
		list = []model.SysDictType{}
	}
	api.Send(r, api.OkData(list))
}

// DictTypeAdd POST /system/dict/type（system:dict:add）。@Validated → 类型唯一 → insertDictType。
func DictTypeAdd(r *ghttp.Request) {
	var dict model.SysDictType
	if err := r.Parse(&dict); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateDictType(&dict); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDictTypeUnique(ctx, &dict) {
		api.Send(r, api.Err("新增字典'"+dict.DictName+"'失败，字典类型已存在"))
		return
	}
	api.Send(r, toAjax(service.InsertDictType(ctx, &dict, lu.Username)))
}

// DictTypeEdit PUT /system/dict/type（system:dict:edit）。@Validated → 类型唯一 → 改名级联 → updateDictType。
func DictTypeEdit(r *ghttp.Request) {
	var dict model.SysDictType
	if err := r.Parse(&dict); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateDictType(&dict); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDictTypeUnique(ctx, &dict) {
		api.Send(r, api.Err("修改字典'"+dict.DictName+"'失败，字典类型已存在"))
		return
	}
	rows, msg := service.UpdateDictType(ctx, &dict, lu.Username)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// DictTypeRemove DELETE /system/dict/type/{dictIds}（system:dict:remove）。整批已分配护栏后批量硬删；成功恒回操作成功。
func DictTypeRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.Get("dictIds").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("字典类型 ID 非法"))
		return
	}
	if _, msg := service.DeleteDictTypeByIds(ctx, ids); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.Ok())
}

// DictTypeRefreshCache DELETE /system/dict/type/refreshCache（system:dict:refresh）。清理 sys_dict:* 缓存键后回操作成功。
func DictTypeRefreshCache(r *ghttp.Request) {
	service.ResetDictCache(r.Context())
	api.Send(r, api.Ok())
}

// ---------------- 字典数据 /system/dict/data ----------------

// DictDataList GET /system/dict/data/list（system:dict:list）→ TableDataInfo（分页，默认 dict_sort 升序）。
func DictDataList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.DictDataListFilter{
		DictType:  r.Get("dictType").String(),
		DictLabel: r.Get("dictLabel").String(),
		Status:    r.Get("status").String(),
	}
	rows, total, err := service.SelectDictDataListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// DictDataGetInfo GET /system/dict/data/{dictCode}（system:dict:query）→ data:{dictData}。
func DictDataGetInfo(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectDictDataById(r.Context(), r.Get("dictCode").Int64())))
}

// DictDataType GET /system/dict/data/type/{dictType}（Java 无 @PreAuthorize，仅需登录态）→ data:[SysDictData...]。
// 复刻 dictType 端点：敏感字典黑名单命中且无 system:dict:list 权限 → 拒绝（文案含 dictType，与 Java 一致）；
// 否则返回列表，null 归一为空数组（对齐 StringUtils.isNull→new ArrayList）。
func DictDataType(r *ghttp.Request) {
	ctx := r.Context()
	dt := r.GetRouter("dictType").String()
	if service.IsSensitiveDictType(ctx, dt) {
		lu := middleware.CurrentLoginUser(r)
		var perms []string
		if lu != nil {
			perms = lu.Permissions
		}
		if !service.HasPermission(perms, "system:dict:list") {
			gLogWarnf(ctx, "用户[%s]无权限读取敏感字典类型[%s]", luName(lu), dt)
			api.Send(r, api.Err("字典类型需要权限才能读取："+dt))
			return
		}
	}
	data := service.SelectDictDataByType(ctx, dt)
	if data == nil {
		data = []model.SysDictData{}
	}
	api.Send(r, api.OkData(data))
}

// DictDataAdd POST /system/dict/data（system:dict:add）。@Validated → 键值唯一 → insertDictData(类型存在+唯一)。
func DictDataAdd(r *ghttp.Request) {
	var data model.SysDictData
	if err := r.Parse(&data); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateDictData(&data); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDictValueUnique(ctx, &data) {
		api.Send(r, api.Err("新增字典数据'"+data.DictLabel+"'失败，同类型下键值已存在："+data.DictValue))
		return
	}
	rows, msg := service.InsertDictData(ctx, &data, lu.Username, dictSortPresent(r))
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// DictDataEdit PUT /system/dict/data（system:dict:edit）。@Validated → 键值唯一 → updateDictData(类型存在+唯一)。
func DictDataEdit(r *ghttp.Request) {
	var data model.SysDictData
	if err := r.Parse(&data); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateDictData(&data); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckDictValueUnique(ctx, &data) {
		api.Send(r, api.Err("修改字典数据'"+data.DictLabel+"'失败，同类型下键值已存在："+data.DictValue))
		return
	}
	rows, msg := service.UpdateDictData(ctx, &data, lu.Username, dictSortPresent(r))
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// DictDataRemove DELETE /system/dict/data/{dictCodes}（system:dict:remove）。整批存在性预校验后逐个硬删；成功恒回操作成功。
func DictDataRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.Get("dictCodes").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("字典数据 ID 非法"))
		return
	}
	if _, msg := service.DeleteDictDataByIds(ctx, ids); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.Ok())
}

// ---------------- 校验与辅助 ----------------

// dictSortPresent 判定 body 是否显式携带非空 dictSort（缺省即不写该列，交 DB 默认，避免编辑漏传被清零）。
func dictSortPresent(r *ghttp.Request) bool {
	v := r.Get("dictSort")
	return !v.IsNil() && v.String() != ""
}

// luName 安全取登录用户名（nil 回空串，仅用于日志）。
func luName(lu *model.LoginUser) string {
	if lu == nil {
		return ""
	}
	return lu.Username
}

// gLogWarnf 日志告警包装（对齐 Java logger.warn，nil 安全）。
func gLogWarnf(ctx context.Context, format string, args ...interface{}) {
	g.Log().Warningf(ctx, format, args...)
}

// validateDictType 复刻 SysDictType 的 @Validated（名称 NotBlank+Size100、类型 NotBlank+Size100+Pattern）。
func validateDictType(d *model.SysDictType) []violation {
	var vs []violation
	if d.DictName == "" {
		vs = append(vs, violation{"dictName", "字典名称不能为空"})
	} else if runeLen(d.DictName) > 100 {
		vs = append(vs, violation{"dictName", "字典类型名称长度不能超过100个字符"})
	}
	if d.DictType == "" {
		vs = append(vs, violation{"dictType", "字典类型不能为空"})
	} else {
		if runeLen(d.DictType) > 100 {
			vs = append(vs, violation{"dictType", "字典类型类型长度不能超过100个字符"})
		}
		if !dictTypePattern.MatchString(d.DictType) {
			vs = append(vs, violation{"dictType", "字典类型必须以字母开头，且只能为（小写字母，数字，下滑线）"})
		}
	}
	return vs
}

// validateDictData 复刻 SysDictData 的 @Validated（标签/键值/类型 NotBlank+Size100，cssClass 仅 Size100）。
func validateDictData(d *model.SysDictData) []violation {
	var vs []violation
	if d.DictLabel == "" {
		vs = append(vs, violation{"dictLabel", "字典标签不能为空"})
	} else if runeLen(d.DictLabel) > 100 {
		vs = append(vs, violation{"dictLabel", "字典标签长度不能超过100个字符"})
	}
	if d.DictValue == "" {
		vs = append(vs, violation{"dictValue", "字典键值不能为空"})
	} else if runeLen(d.DictValue) > 100 {
		vs = append(vs, violation{"dictValue", "字典键值长度不能超过100个字符"})
	}
	if d.DictType == "" {
		vs = append(vs, violation{"dictType", "字典类型不能为空"})
	} else if runeLen(d.DictType) > 100 {
		vs = append(vs, violation{"dictType", "字典类型长度不能超过100个字符"})
	}
	if runeLen(d.CssClass) > 100 {
		vs = append(vs, violation{"cssClass", "样式属性长度不能超过100个字符"})
	}
	return vs
}
