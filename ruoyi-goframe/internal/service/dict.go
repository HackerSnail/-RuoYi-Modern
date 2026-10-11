// Package service —— sys_dict_type / sys_dict_data 数据字典。对齐 Java ISysDictTypeService + ISysDictDataService：
// 类型/数据 列表·详情·唯一性·增删改，selectDictDataByType 读取，改名级联、已分配护栏、类型存在校验、缓存清理。
// 取舍：字典读取走 DB 直连、不读写 Redis sys_dict:* 缓存（cache-aside 与否不影响 HTTP 返回值，且规避 Go/Java
// 共享 Redis 的对象序列化不一致）；/refreshCache 仅做“清理 sys_dict:* 键”这一可观测副作用。
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

// ---------------- 字典类型 sys_dict_type ----------------

// selectDictTypeVo 对应 SysDictTypeMapper.xml <sql id="selectDictTypeVo">（不取 update_*）。
const selectDictTypeVo = "select dict_id, dict_name, dict_type, status, create_by, create_time, remark from sys_dict_type"

// DictTypeListFilter 字典类型列表条件（名称模糊 / 类型模糊 / 状态 / 创建时间范围）。
type DictTypeListFilter struct {
	DictName  string
	DictType  string
	Status    string
	BeginTime string
	EndTime   string
}

// buildDictTypeListSQL 组装列表查询（sys_dict_type 无 del_flag，硬删；<where> 逐条 AND，无默认排序）。
func buildDictTypeListSQL(f DictTypeListFilter) (string, []interface{}) {
	sql := selectDictTypeVo
	conds := make([]string, 0, 5)
	args := make([]interface{}, 0, 5)
	if f.DictName != "" {
		conds = append(conds, "dict_name like concat('%', ?, '%')")
		args = append(args, f.DictName)
	}
	if f.Status != "" {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if f.DictType != "" {
		conds = append(conds, "dict_type like concat('%', ?, '%')")
		args = append(args, f.DictType)
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

func scanDictTypes(recs gdb.Result) []model.SysDictType {
	out := make([]model.SysDictType, 0, len(recs))
	for _, r := range recs {
		var d model.SysDictType
		if gconv.Struct(r.Map(), &d) == nil {
			out = append(out, d)
		}
	}
	return out
}

// SelectDictTypeList 对应 selectDictTypeList（不分页）。
func SelectDictTypeList(ctx context.Context, f DictTypeListFilter) []model.SysDictType {
	sql, args := buildDictTypeListSQL(f)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanDictTypes(recs)
}

// SelectDictTypeListPaged 对应 list 端点 startPage + selectDictTypeList。
func SelectDictTypeListPaged(ctx context.Context, f DictTypeListFilter, p Page) ([]model.SysDictType, int64, error) {
	sql, args := buildDictTypeListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanDictTypes(res.Rows), res.Total, nil
}

// SelectDictTypeAll 对应 selectDictTypeAll（optionselect：无过滤全量）。
func SelectDictTypeAll(ctx context.Context) []model.SysDictType {
	recs, err := g.DB().GetAll(ctx, selectDictTypeVo)
	if err != nil {
		return nil
	}
	return scanDictTypes(recs)
}

// SelectDictTypeById 对应 selectDictTypeById。不存在回 nil。
func SelectDictTypeById(ctx context.Context, dictId int64) *model.SysDictType {
	rec, err := g.DB().GetOne(ctx, selectDictTypeVo+" where dict_id = ?", dictId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDictType
	if gconv.Struct(rec.Map(), &d) != nil {
		return nil
	}
	return &d
}

// SelectDictTypeByType 对应 selectDictTypeByType（数据新增/修改前的类型存在性校验）。不存在回 nil。
func SelectDictTypeByType(ctx context.Context, dictType string) *model.SysDictType {
	if dictType == "" {
		return nil
	}
	rec, err := g.DB().GetOne(ctx, selectDictTypeVo+" where dict_type = ?", dictType)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDictType
	if gconv.Struct(rec.Map(), &d) != nil {
		return nil
	}
	return &d
}

// uniqueDictTypeIdByType 复用 checkDictTypeUnique 的 limit 1 短路查询，返回占用该 dict_type 的 dict_id（无则 -1）。
func uniqueDictTypeIdByType(ctx context.Context, dictType string) int64 {
	if dictType == "" {
		return -1
	}
	rec, err := g.DB().GetOne(ctx, "select dict_id from sys_dict_type where dict_type = ? limit 1", dictType)
	if err != nil || rec == nil || rec.IsEmpty() {
		return -1
	}
	return gconv.Int64(rec.Map()["dict_id"])
}

// CheckDictTypeUnique 对应 checkDictTypeUnique（编辑自匹配复用 uniqueSelfOrNone）。
func CheckDictTypeUnique(ctx context.Context, dict *model.SysDictType) bool {
	return uniqueSelfOrNone(dict.DictId, uniqueDictTypeIdByType(ctx, dict.DictType))
}

// InsertDictType 对应 insertDictType：动态非空列插入，create_time 刷新，回写自增 dictId。
func InsertDictType(ctx context.Context, dict *model.SysDictType, createBy string) int {
	cols := make([]string, 0, 6)
	vals := make([]interface{}, 0, 6)
	add := func(col string, val interface{}) { cols = append(cols, col); vals = append(vals, val) }
	if dict.DictName != "" {
		add("dict_name", dict.DictName)
	}
	if dict.DictType != "" {
		add("dict_type", dict.DictType)
	}
	if dict.Status != "" {
		add("status", dict.Status)
	}
	if dict.Remark != "" {
		add("remark", dict.Remark)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	add("create_time", gtime.Now())
	sql := "insert into sys_dict_type(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	res, err := g.DB().Exec(ctx, sql, vals...)
	if err != nil {
		return 0
	}
	if id, e := res.LastInsertId(); e == nil {
		dict.DictId = id
	}
	return affectedOf(res)
}

// UpdateDictType 对应 updateDictType（@Transactional）：改名时级联更新 sys_dict_data.dict_type，再更新类型本身。
// 前置：目标 dict_type 唯一（Controller 已校，此处纵深）、原类型必存在。返回 (影响行数, 拒绝文案)。
func UpdateDictType(ctx context.Context, dict *model.SysDictType, updateBy string) (int, string) {
	if !CheckDictTypeUnique(ctx, dict) {
		return 0, "修改字典'" + dict.DictName + "'失败，字典类型已存在：" + dict.DictType
	}
	var oldType string
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		oldRec, e := tx.GetOne(selectDictTypeVo + " where dict_id = ?" /*nolint*/, dict.DictId)
		if oldRec == nil || oldRec.IsEmpty() {
			return fmt.Errorf("NOTFOUND")
		}
		if e != nil {
			return e
		}
		oldType = gconv.String(oldRec.Map()["dict_type"])
		// 类型标识被改名 → 级联更新其下字典数据的 dict_type
		if oldType != dict.DictType {
			if _, e := tx.Exec("update sys_dict_data set dict_type = ? where dict_type = ?", dict.DictType, oldType); e != nil {
				return e
			}
		}
		sets := make([]string, 0, 5)
		args := make([]interface{}, 0, 5)
		upd := func(clause string, val interface{}) { sets = append(sets, clause); args = append(args, val) }
		if dict.DictName != "" {
			upd("dict_name = ?", dict.DictName)
		}
		if dict.DictType != "" {
			upd("dict_type = ?", dict.DictType)
		}
		if dict.Status != "" {
			upd("status = ?", dict.Status)
		}
		if dict.Remark != "" {
			upd("remark = ?", dict.Remark)
		}
		if updateBy != "" {
			upd("update_by = ?", updateBy)
		}
		upd("update_time = ?", gtime.Now())
		args = append(args, dict.DictId)
		_, e = tx.Exec("update sys_dict_type set "+joinStrings(sets, ", ")+" where dict_id = ?", args...)
		return e
	})
	if err != nil {
		if err.Error() == "NOTFOUND" {
			return 0, "字典类型不存在或已被删除，dictId=" + gconv.String(dict.DictId)
		}
		return 0, ""
	}
	return 1, ""
}

// DeleteDictTypeByIds 对应 deleteDictTypeByIds（@Transactional 原子）：整批预校验，任一待删类型已分配字典数据则整体拒绝；
// 否则批量硬删。返回 (影响行数, 拒绝文案)；文案非空时未执行任何删除。
func DeleteDictTypeByIds(ctx context.Context, dictIds []int64) (int, string) {
	if len(dictIds) == 0 {
		return 0, ""
	}
	recs, err := g.DB().GetAll(ctx, selectDictTypeVo+" where dict_id in ("+placeholders(len(dictIds))+")", idsToInterfaces(dictIds)...)
	if err != nil {
		return 0, ""
	}
	dictTypes := scanDictTypes(recs)
	types := make([]string, 0, len(dictTypes))
	for _, d := range dictTypes {
		types = append(types, d.DictType)
	}
	// 批量筛出已被 sys_dict_data 占用（已分配）的类型
	assigned := map[string]bool{}
	if len(types) > 0 {
		ph := placeholders(len(types))
		strArgs := make([]interface{}, 0, len(types))
		for _, t := range types {
			strArgs = append(strArgs, t)
		}
		arecs, e := g.DB().GetAll(ctx, "select distinct dict_type from sys_dict_data where dict_type in ("+ph+")", strArgs...)
		if e == nil {
			for _, r := range arecs {
				assigned[gconv.String(r.Map()["dict_type"])] = true
			}
		}
	}
	for _, d := range dictTypes {
		if assigned[d.DictType] {
			return 0, fmt.Sprintf("%s已分配,不能删除", d.DictName)
		}
	}
	res, err := g.DB().Exec(ctx, "delete from sys_dict_type where dict_id in ("+placeholders(len(dictIds))+")", idsToInterfaces(dictIds)...)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// ---------------- 字典数据 sys_dict_data ----------------

// selectDictDataVo 对应 SysDictDataMapper.xml <sql id="selectDictDataVo">（不取 update_*）。
const selectDictDataVo = "select dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark from sys_dict_data"

// DictDataListFilter 字典数据列表条件（类型精确 / 标签模糊 / 状态）。
type DictDataListFilter struct {
	DictType  string
	DictLabel string
	Status    string
}

// buildDictDataListSQL 组装列表查询（默认 order by dict_sort asc，由调用方 defaultOrder 传入）。
func buildDictDataListSQL(f DictDataListFilter) (string, []interface{}) {
	sql := selectDictDataVo
	conds := make([]string, 0, 3)
	args := make([]interface{}, 0, 3)
	if f.DictType != "" {
		conds = append(conds, "dict_type = ?")
		args = append(args, f.DictType)
	}
	if f.DictLabel != "" {
		conds = append(conds, "dict_label like concat('%', ?, '%')")
		args = append(args, f.DictLabel)
	}
	if f.Status != "" {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if len(conds) > 0 {
		sql += " where " + joinStrings(conds, " AND ")
	}
	return sql, args
}

func scanDictData(recs gdb.Result) []model.SysDictData {
	out := make([]model.SysDictData, 0, len(recs))
	for _, r := range recs {
		var d model.SysDictData
		if gconv.Struct(r.Map(), &d) == nil {
			d.Default = d.IsDefault == consts.Yes
			out = append(out, d)
		}
	}
	return out
}

// SelectDictDataList 对应 selectDictDataList（不分页，order by dict_sort asc）。
func SelectDictDataList(ctx context.Context, f DictDataListFilter) []model.SysDictData {
	sql, args := buildDictDataListSQL(f)
	sql += " order by dict_sort asc"
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanDictData(recs)
}

// SelectDictDataListPaged 对应 data list 端点 startPage + selectDictDataList（默认排序 dict_sort asc）。
func SelectDictDataListPaged(ctx context.Context, f DictDataListFilter, p Page) ([]model.SysDictData, int64, error) {
	sql, args := buildDictDataListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "dict_sort asc")
	if err != nil {
		return nil, 0, err
	}
	return scanDictData(res.Rows), res.Total, nil
}

// SelectDictDataByType 对应 selectDictDataByType：status='0' 且指定类型，按 dict_sort 升序（DB 直连，不走缓存）。
func SelectDictDataByType(ctx context.Context, dictType string) []model.SysDictData {
	recs, err := g.DB().GetAll(ctx, selectDictDataVo+" where status = '0' and dict_type = ? order by dict_sort asc", dictType)
	if err != nil {
		return nil
	}
	return scanDictData(recs)
}

// SelectDictDataById 对应 selectDictDataById。不存在回 nil。
func SelectDictDataById(ctx context.Context, dictCode int64) *model.SysDictData {
	rec, err := g.DB().GetOne(ctx, selectDictDataVo+" where dict_code = ?", dictCode)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDictData
	if gconv.Struct(rec.Map(), &d) != nil {
		return nil
	}
	d.Default = d.IsDefault == consts.Yes
	return &d
}

// uniqueDictValueCode 复用 checkDictValueUnique 的 limit 1 短路查询，返回同类型同键值占用者的 dict_code（无则 -1）。
func uniqueDictValueCode(ctx context.Context, dictType, dictValue string) int64 {
	if dictType == "" || dictValue == "" {
		return -1
	}
	rec, err := g.DB().GetOne(ctx, "select dict_code from sys_dict_data where dict_type = ? and dict_value = ? limit 1", dictType, dictValue)
	if err != nil || rec == nil || rec.IsEmpty() {
		return -1
	}
	return gconv.Int64(rec.Map()["dict_code"])
}

// CheckDictValueUnique 对应 checkDictValueUnique（编辑自匹配复用 uniqueSelfOrNone，dictCode 归一到 -1）。
func CheckDictValueUnique(ctx context.Context, data *model.SysDictData) bool {
	return uniqueSelfOrNone(data.DictCode, uniqueDictValueCode(ctx, data.DictType, data.DictValue))
}

// assertDictTypeExists 对应 assertDictTypeExists：字典类型必须存在于 sys_dict_type，防孤儿字典项。
func assertDictTypeExists(ctx context.Context, dictType string) bool {
	return dictType != "" && SelectDictTypeByType(ctx, dictType) != nil
}

// InsertDictData 对应 insertDictData：类型存在校验（纵深）→ 键值唯一（纵深）→ 动态非空列插入，回写 dictCode。
// 返回 (影响行数, 拒绝文案)；文案非空时未写库。
func InsertDictData(ctx context.Context, data *model.SysDictData, createBy string, dictSortPresent bool) (int, string) {
	if !assertDictTypeExists(ctx, data.DictType) {
		return 0, "字典类型不存在：" + data.DictType
	}
	if !CheckDictValueUnique(ctx, data) {
		return 0, "新增字典数据'" + data.DictLabel + "'失败，同类型下键值已存在：" + data.DictValue
	}
	cols := make([]string, 0, 11)
	vals := make([]interface{}, 0, 11)
	add := func(col string, val interface{}) { cols = append(cols, col); vals = append(vals, val) }
	if dictSortPresent {
		add("dict_sort", data.DictSort)
	}
	if data.DictLabel != "" {
		add("dict_label", data.DictLabel)
	}
	if data.DictValue != "" {
		add("dict_value", data.DictValue)
	}
	if data.DictType != "" {
		add("dict_type", data.DictType)
	}
	if data.CssClass != "" {
		add("css_class", data.CssClass)
	}
	if data.ListClass != "" {
		add("list_class", data.ListClass)
	}
	if data.IsDefault != "" {
		add("is_default", data.IsDefault)
	}
	if data.Status != "" {
		add("status", data.Status)
	}
	if data.Remark != "" {
		add("remark", data.Remark)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	add("create_time", gtime.Now())
	sql := "insert into sys_dict_data(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	res, err := g.DB().Exec(ctx, sql, vals...)
	if err != nil {
		return 0, ""
	}
	if id, e := res.LastInsertId(); e == nil {
		data.DictCode = id
	}
	return affectedOf(res), ""
}

// UpdateDictData 对应 updateDictData：类型存在校验 → 键值唯一 → 动态 SET 仅更新非空字段，update_time 刷新。
// 返回 (影响行数, 拒绝文案)；文案非空时未写库。
func UpdateDictData(ctx context.Context, data *model.SysDictData, updateBy string, dictSortPresent bool) (int, string) {
	if !assertDictTypeExists(ctx, data.DictType) {
		return 0, "字典类型不存在：" + data.DictType
	}
	if !CheckDictValueUnique(ctx, data) {
		return 0, "修改字典数据'" + data.DictLabel + "'失败，同类型下键值已存在：" + data.DictValue
	}
	sets := make([]string, 0, 11)
	args := make([]interface{}, 0, 11)
	upd := func(clause string, val interface{}) { sets = append(sets, clause); args = append(args, val) }
	if dictSortPresent {
		upd("dict_sort = ?", data.DictSort)
	}
	if data.DictLabel != "" {
		upd("dict_label = ?", data.DictLabel)
	}
	if data.DictValue != "" {
		upd("dict_value = ?", data.DictValue)
	}
	if data.DictType != "" {
		upd("dict_type = ?", data.DictType)
	}
	if data.CssClass != "" {
		upd("css_class = ?", data.CssClass)
	}
	if data.ListClass != "" {
		upd("list_class = ?", data.ListClass)
	}
	if data.IsDefault != "" {
		upd("is_default = ?", data.IsDefault)
	}
	if data.Status != "" {
		upd("status = ?", data.Status)
	}
	if data.Remark != "" {
		upd("remark = ?", data.Remark)
	}
	if updateBy != "" {
		upd("update_by = ?", updateBy)
	}
	upd("update_time = ?", gtime.Now())
	args = append(args, data.DictCode)
	sql := "update sys_dict_data set " + joinStrings(sets, ", ") + " where dict_code = ?"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// DeleteDictDataByIds 对应 deleteDictDataByIds（@Transactional）：整批预校验任一记录必存在，再逐个硬删。
// 返回 (影响行数, 拒绝文案)；文案非空时未执行任何删除。
func DeleteDictDataByIds(ctx context.Context, dictCodes []int64) (int, string) {
	if len(dictCodes) == 0 {
		return 0, ""
	}
	for _, dictCode := range dictCodes {
		if SelectDictDataById(ctx, dictCode) == nil {
			return 0, "字典数据不存在或已被删除，dictCode=" + gconv.String(dictCode)
		}
	}
	n := 0
	for _, dictCode := range dictCodes {
		res, err := g.DB().Exec(ctx, "delete from sys_dict_data where dict_code = ?", dictCode)
		if err == nil {
			n += affectedOf(res)
		}
	}
	return n, ""
}

// ResetDictCache 对应 resetDictCache 的 clearDictCache 部分：SCAN sys_dict:* 后 UNLINK（分批非阻塞）。
// 取舍见包注释：Go 侧不回填 Redis，缓存重建由读取端 DB 直连天然保证，故此仅保留“清理”这一可观测副作用。
func ResetDictCache(ctx context.Context) {
	pattern := consts.RedisSysDict + "*"
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

// sensitiveDictTypes 读敏感字典类型黑名单（对应 ruoyi.sensitiveDictTypes，逗号分隔；缺省为空=全部开放）。
func sensitiveDictTypes(ctx context.Context) map[string]bool {
	s := g.Cfg().MustGet(ctx, "ruoyi.sensitiveDictTypes", "").String()
	set := map[string]bool{}
	for _, k := range strings.Split(s, ",") {
		if k = strings.TrimSpace(k); k != "" {
			set[k] = true
		}
	}
	return set
}

// IsSensitiveDictType 判定某字典类型是否需 system:dict:list 鉴权才能公开读取。
func IsSensitiveDictType(ctx context.Context, dictType string) bool {
	return sensitiveDictTypes(ctx)[dictType]
}
