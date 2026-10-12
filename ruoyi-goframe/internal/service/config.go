// Package service —— sys_config 参数配置。对齐 Java ISysConfigService：
// selectConfigByKey / selectCaptchaEnabled 等读取，及 /system/config 列表/详情/唯一性/增删改/刷新缓存。
// 取舍：参数读取走 DB 直连、不读写 Redis sys_config:* 值缓存（缓存与否不影响 HTTP 返回值，且规避 Go/Java
// 共享 Redis 的值序列化不一致）；/refreshCache 仅做“清理 sys_config:* 键”这一可观测副作用。
package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// selectConfigByKey 按 config_key 取 config_value；不存在返回空串。
func selectConfigByKey(ctx context.Context, key string) string {
	v, err := g.DB().Model("sys_config").Ctx(ctx).Fields("config_value").Where("config_key = ?", key).Value()
	if err != nil || v == nil || v.IsNil() {
		return ""
	}
	return strings.TrimSpace(v.String())
}

// getCaptchaEnabled 对应 selectCaptchaEnabled：sys.account.captchaEnabled == "true"。
func getCaptchaEnabled(ctx context.Context) bool {
	return strings.EqualFold(selectConfigByKey(ctx, "sys.account.captchaEnabled"), "true")
}

// GetCaptchaEnabled 对外暴露验证码开关（供 /captchaImage 回传 captchaEnabled）。
func GetCaptchaEnabled(ctx context.Context) bool { return getCaptchaEnabled(ctx) }

// IsRegisterEnabled 对应 SysRegisterController 的注册开关判定：sys.account.registerUser == "true"。
// 严格区分大小写（照 Java "".equals("true")，不用 EqualFold）；未配置/非 "true" 一律视为关闭。
func IsRegisterEnabled(ctx context.Context) bool {
	return selectConfigByKey(ctx, "sys.account.registerUser") == "true"
}

// getIntConfig 取整型配置，空/非法返回 defaultVal。对应 Convert.toInt 兜底。
func getIntConfig(ctx context.Context, key string, defaultVal int) int {
	s := selectConfigByKey(ctx, key)
	if s == "" {
		return defaultVal
	}
	n, err := parseIntStrict(s)
	if err != nil {
		return defaultVal
	}
	return n
}

func parseIntStrict(s string) (int, error) {
	var n int
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, errBadInt
		}
		n = n*10 + int(c-'0')
	}
	return n, nil
}

var errBadInt = &stringErr{"invalid int config"}

type stringErr struct{ msg string }

func (e *stringErr) Error() string { return e.msg }

// selectConfigVo 对应 SysConfigMapper.xml <sql id="selectConfigVo">（全列，含 update_by/update_time/remark）。
const selectConfigVo = "select config_id, config_name, config_key, config_value, config_type, create_by, create_time, update_by, update_time, remark from sys_config"

// ConfigListFilter 参数列表条件（名称模糊 / 类型精确 / 键名模糊 / 创建时间范围）。
type ConfigListFilter struct {
	ConfigName string
	ConfigType string
	ConfigKey  string
	BeginTime  string
	EndTime    string
}

// buildConfigListSQL 组装列表查询（sys_config 无 del_flag，硬删；<where> 逐条 AND 拼接，无默认排序）。
func buildConfigListSQL(f ConfigListFilter) (string, []interface{}) {
	sql := selectConfigVo
	conds := make([]string, 0, 5)
	args := make([]interface{}, 0, 5)
	if f.ConfigName != "" {
		conds = append(conds, "config_name like concat('%', ?, '%')")
		args = append(args, f.ConfigName)
	}
	if f.ConfigType != "" {
		conds = append(conds, "config_type = ?")
		args = append(args, f.ConfigType)
	}
	if f.ConfigKey != "" {
		conds = append(conds, "config_key like concat('%', ?, '%')")
		args = append(args, f.ConfigKey)
	}
	if f.BeginTime != "" {
		conds = append(conds, "date_format(create_time,'%Y%m%d') >= date_format(?, '%Y%m%d')")
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		conds = append(conds, "date_format(create_time,'%Y%m%d') <= date_format(?, '%Y%m%d')")
		args = append(args, f.EndTime)
	}
	if len(conds) > 0 {
		sql += " where " + joinStrings(conds, " AND ")
	}
	return sql, args
}

func scanConfigs(recs gdb.Result) []model.SysConfig {
	out := make([]model.SysConfig, 0, len(recs))
	for _, r := range recs {
		var c model.SysConfig
		if gconv.Struct(r.Map(), &c) == nil {
			out = append(out, c)
		}
	}
	return out
}

// SelectConfigList 对应 selectConfigList（不分页）。
func SelectConfigList(ctx context.Context, f ConfigListFilter) []model.SysConfig {
	sql, args := buildConfigListSQL(f)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanConfigs(recs)
}

// SelectConfigListPaged 对应 list 端点 startPage + selectConfigList。
func SelectConfigListPaged(ctx context.Context, f ConfigListFilter, p Page) ([]model.SysConfig, int64, error) {
	sql, args := buildConfigListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanConfigs(res.Rows), res.Total, nil
}

// SelectConfigById 对应 selectConfigById（service 包装：按 config_id 定位）。不存在回 nil。
func SelectConfigById(ctx context.Context, configId int64) *model.SysConfig {
	rec, err := g.DB().GetOne(ctx, selectConfigVo+" where config_id = ?", configId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var c model.SysConfig
	if gconv.Struct(rec.Map(), &c) != nil {
		return nil
	}
	return &c
}

// uniqueConfigIdBy 复用 checkConfigKeyUnique 的 limit 1 短路查询，返回占用该键名的 config_id（无则 -1）。
func uniqueConfigIdBy(ctx context.Context, key string) int64 {
	if key == "" {
		return -1
	}
	rec, err := g.DB().GetOne(ctx, "select config_id from sys_config where config_key = ? limit 1", key)
	if err != nil || rec == nil || rec.IsEmpty() {
		return -1
	}
	return gconv.Int64(rec.Map()["config_id"])
}

// CheckConfigKeyUnique 对应 checkConfigKeyUnique（编辑自匹配复用 uniqueSelfOrNone）。
func CheckConfigKeyUnique(ctx context.Context, config *model.SysConfig) bool {
	return uniqueSelfOrNone(config.ConfigId, uniqueConfigIdBy(ctx, config.ConfigKey))
}

// InsertConfig 对应 insertConfig：动态非空列插入，create_time 刷新，回写自增 configId。
func InsertConfig(ctx context.Context, config *model.SysConfig, createBy string) int {
	cols := make([]string, 0, 7)
	vals := make([]interface{}, 0, 7)
	add := func(col string, val interface{}) { cols = append(cols, col); vals = append(vals, val) }
	if config.ConfigName != "" {
		add("config_name", config.ConfigName)
	}
	if config.ConfigKey != "" {
		add("config_key", config.ConfigKey)
	}
	if config.ConfigValue != "" {
		add("config_value", config.ConfigValue)
	}
	if config.ConfigType != "" {
		add("config_type", config.ConfigType)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	if config.Remark != "" {
		add("remark", config.Remark)
	}
	add("create_time", gtime.Now())
	sql := "insert into sys_config(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	res, err := g.DB().Exec(ctx, sql, vals...)
	if err != nil {
		return 0
	}
	if id, e := res.LastInsertId(); e == nil {
		config.ConfigId = id
	}
	return affectedOf(res)
}

// UpdateConfig 对应 updateConfig：动态 SET 仅更新非空字段，update_time 强制刷新。
func UpdateConfig(ctx context.Context, config *model.SysConfig, updateBy string) int {
	sets := make([]string, 0, 7)
	args := make([]interface{}, 0, 7)
	add := func(clause string, val interface{}) { sets = append(sets, clause); args = append(args, val) }
	if config.ConfigName != "" {
		add("config_name = ?", config.ConfigName)
	}
	if config.ConfigKey != "" {
		add("config_key = ?", config.ConfigKey)
	}
	if config.ConfigValue != "" {
		add("config_value = ?", config.ConfigValue)
	}
	if config.ConfigType != "" {
		add("config_type = ?", config.ConfigType)
	}
	if updateBy != "" {
		add("update_by = ?", updateBy)
	}
	if config.Remark != "" {
		add("remark = ?", config.Remark)
	}
	sets = append(sets, "update_time = ?")
	args = append(args, gtime.Now())
	sql := "update sys_config set " + joinStrings(sets, ", ") + " where config_id = ?"
	args = append(args, config.ConfigId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0
	}
	return affectedOf(res)
}

// DeleteConfigByIds 对应 deleteConfigByIds：先整批预校验（不存在 / 内置项 Y 均拒绝，无部分删除），再逐个硬删。
// 返回 (删除行数, 拒绝文案)；文案非空时未执行任何删除。
func DeleteConfigByIds(ctx context.Context, configIds []int64) (int, string) {
	targets := make([]*model.SysConfig, 0, len(configIds))
	for _, configId := range configIds {
		config := SelectConfigById(ctx, configId)
		if config == nil {
			return 0, "参数不存在或已被删除，configId=" + gconv.String(configId)
		}
		if config.ConfigType == consts.Yes {
			return 0, fmt.Sprintf("内置参数【%s】不能删除 ", config.ConfigKey)
		}
		targets = append(targets, config)
	}
	n := 0
	for _, config := range targets {
		res, err := g.DB().Exec(ctx, "delete from sys_config where config_id = ?", config.ConfigId)
		if err == nil {
			n += affectedOf(res)
		}
	}
	return n, ""
}

// publicConfigKeys 读公开读取白名单（对应 ruoyi.publicConfigKeys，逗号分隔；缺省为前端登录页两个开关）。
func publicConfigKeys(ctx context.Context) map[string]bool {
	s := g.Cfg().MustGet(ctx, "ruoyi.publicConfigKeys", "sys.account.captchaEnabled,sys.account.registerUser").String()
	set := map[string]bool{}
	for _, k := range strings.Split(s, ",") {
		if k = strings.TrimSpace(k); k != "" {
			set[k] = true
		}
	}
	return set
}

// SelectPublicConfigByKey 对应 selectPublicConfigByKey：先过白名单再取值。不在白名单 → 回错误文案
// （控制器转 code500/HTTP400，且不回显用户可控的 configKey 以免日志注入/XSS 面，与 Java 一致）。
func SelectPublicConfigByKey(ctx context.Context, key string) (string, string) {
	if !publicConfigKeys(ctx)[key] {
		g.Log().Warningf(ctx, "参数键[%s]不在公开读取白名单内，已拒绝", key)
		return "", "参数键不允许通过公开接口读取"
	}
	return selectConfigByKey(ctx, key), ""
}

// ResetConfigCache 对应 resetConfigCache 的 clearConfigCache 部分：SCAN sys_config:* 后 UNLINK（分批非阻塞）。
// 取舍见包注释：Go 侧不回填 Redis，缓存重建由读取端 DB 直连天然保证，故此仅保留“清理”这一可观测副作用。
func ResetConfigCache(ctx context.Context) {
	pattern := consts.RedisSysConfig + "*"
	var cursor int64
	for {
		reply, err := g.Redis().Do(ctx, "SCAN", cursor, "MATCH", pattern, "COUNT", 200)
		if err != nil {
			return
		}
		arr := reply.Array()
		if len(arr) < 2 {
			return
		}
		cursor = gconv.Int64(arr[0])
		for _, k := range gconv.SliceStr(arr[1]) {
			_, _ = g.Redis().Do(ctx, "UNLINK", k)
		}
		if cursor == 0 {
			return
		}
	}
}
