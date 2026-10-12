// Package service —— 代码生成主表 mapper，逐条对齐 GenTableMapper.xml 的语义（GoFrame g.DB() 原生 SQL）。
//
// 关键点（不得错位）：
//   - selectGenTableList/dbList 的 lower(...) like 大小写不敏感过滤 + params.beginTime/endTime 日期范围；
//     dbList 从 information_schema.tables 排除 qrtz\_%/gen\_% 与已导入表，按 create_time desc。
//   - selectGenTableById/ByName/All 用主表 LEFT JOIN gen_table_column 一次装配 columns（对齐 MyBatis collection：
//     column_id 为空即该表无列，不追加）。All 不含 gen_type/gen_path（忠实照 XML）。
//   - insert/update 动态列判空口径逐字对齐 XML；自增 table_id 回写；create_time/update_time 走 sysdate()。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectGenTableVo 对齐 <sql id="selectGenTableVo">（22 列，从 gen_table）。
const selectGenTableVo = "select table_id, table_name, table_comment, sub_table_name, sub_table_fk_name, " +
	"class_name, tpl_category, tpl_web_type, package_name, module_name, business_name, function_name, " +
	"function_author, form_col_num, gen_type, gen_path, options, create_by, create_time, update_by, " +
	"update_time, remark from gen_table"

// genTableJoinColumns 对齐 selectGenTableById/ByName：t.* + c.*（不取 c.table_id，tableId 由 t.table_id 承载）。
const genTableJoinColumns = "SELECT t.table_id, t.table_name, t.table_comment, t.sub_table_name, " +
	"t.sub_table_fk_name, t.class_name, t.tpl_category, t.tpl_web_type, t.package_name, t.module_name, " +
	"t.business_name, t.function_name, t.function_author, t.form_col_num, t.gen_type, t.gen_path, " +
	"t.options, t.remark, c.column_id, c.column_name, c.column_comment, c.column_type, c.java_type, " +
	"c.java_field, c.is_pk, c.is_increment, c.is_required, c.is_insert, c.is_edit, c.is_list, c.is_query, " +
	"c.query_type, c.html_type, c.dict_type, c.sort FROM gen_table t LEFT JOIN gen_table_column c " +
	"ON t.table_id = c.table_id"

// genTableAllJoinColumns 对齐 selectGenTableAll：不含 t.gen_type/t.gen_path（忠实照 XML）。
const genTableAllJoinColumns = "SELECT t.table_id, t.table_name, t.table_comment, t.sub_table_name, " +
	"t.sub_table_fk_name, t.class_name, t.tpl_category, t.tpl_web_type, t.package_name, t.module_name, " +
	"t.business_name, t.function_name, t.function_author, t.form_col_num, t.options, t.remark, " +
	"c.column_id, c.column_name, c.column_comment, c.column_type, c.java_type, c.java_field, c.is_pk, " +
	"c.is_increment, c.is_required, c.is_insert, c.is_edit, c.is_list, c.is_query, c.query_type, " +
	"c.html_type, c.dict_type, c.sort FROM gen_table t LEFT JOIN gen_table_column c ON t.table_id = c.table_id"

// GenTableListFilter 已导入生成表 / 可导入实表 的公共过滤条件（字符串 presence 区分未提交与空串）。
type GenTableListFilter struct {
	TableName    string
	TableComment string
	BeginTime    string
	EndTime      string
}

// scanGenTable 从「仅主表列」的行装配 GenTable（不含 columns，对齐 selectGenTableList/dbList 的 resultMap 无列装配）。
func scanGenTable(rec gdb.Record) *model.GenTable {
	var t model.GenTable
	if gconv.Struct(rec.Map(), &t) != nil {
		return nil
	}
	return &t
}

func scanGenTables(recs gdb.Result) []*model.GenTable {
	out := make([]*model.GenTable, 0, len(recs))
	for _, r := range recs {
		if t := scanGenTable(r); t != nil {
			out = append(out, t)
		}
	}
	return out
}

// assembleJoined 把 LEFT JOIN 结果行按 table_id 归组装配为有序 []*GenTable，每表带 columns。
// 对齐 MyBatis：以 table_id 为分组 id；仅当行含非空 column_id 才追加列（无列的表 columns 为空）。
func assembleJoined(recs gdb.Result) []*model.GenTable {
	order := make([]int64, 0)
	byId := make(map[int64]*model.GenTable)
	for _, r := range recs {
		m := r.Map()
		tableId := gconv.Int64(m["table_id"])
		t, ok := byId[tableId]
		if !ok {
			t = &model.GenTable{}
			// 主表字段（t.* 每行相同，取首行即可）
			_ = gconv.Struct(map[string]interface{}{
				"tableId":        m["table_id"],
				"tableName":      m["table_name"],
				"tableComment":   m["table_comment"],
				"subTableName":   m["sub_table_name"],
				"subTableFkName": m["sub_table_fk_name"],
				"className":      m["class_name"],
				"tplCategory":    m["tpl_category"],
				"tplWebType":     m["tpl_web_type"],
				"packageName":    m["package_name"],
				"moduleName":     m["module_name"],
				"businessName":   m["business_name"],
				"functionName":   m["function_name"],
				"functionAuthor": m["function_author"],
				"formColNum":     m["form_col_num"],
				"genType":        m["gen_type"],
				"genPath":        m["gen_path"],
				"options":        m["options"],
				"remark":         m["remark"],
			}, t)
			byId[tableId] = t
			order = append(order, tableId)
		}
		// 列：column_id 为 NULL（LEFT JOIN 未命中，Map 值为 nil）则跳过（对齐 <id column="column_id">）
		if cid := m["column_id"]; cid != nil && gconv.String(cid) != "" {
			col := &model.GenTableColumn{}
			_ = gconv.Struct(map[string]interface{}{
				"columnId":      cid,
				"tableId":       m["table_id"],
				"columnName":    m["column_name"],
				"columnComment": m["column_comment"],
				"columnType":    m["column_type"],
				"javaType":      m["java_type"],
				"javaField":     m["java_field"],
				"isPk":          m["is_pk"],
				"isIncrement":   m["is_increment"],
				"isRequired":    m["is_required"],
				"isInsert":      m["is_insert"],
				"isEdit":        m["is_edit"],
				"isList":        m["is_list"],
				"isQuery":       m["is_query"],
				"queryType":     m["query_type"],
				"htmlType":      m["html_type"],
				"dictType":      m["dict_type"],
				"sort":          m["sort"],
			}, col)
			t.Columns = append(t.Columns, col)
		}
	}
	out := make([]*model.GenTable, 0, len(order))
	for _, id := range order {
		out = append(out, byId[id])
	}
	return out
}

// buildGenTableListSQL 组装已导入生成表列表的基础查询（不含 ORDER）。
func buildGenTableListSQL(f GenTableListFilter) (string, []interface{}) {
	sql := selectGenTableVo
	conds := make([]string, 0, 4)
	args := make([]interface{}, 0, 4)
	if f.TableName != "" {
		conds = append(conds, "lower(table_name) like lower(concat('%', ?, '%'))")
		args = append(args, f.TableName)
	}
	if f.TableComment != "" {
		conds = append(conds, "lower(table_comment) like lower(concat('%', ?, '%'))")
		args = append(args, f.TableComment)
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
		sql += " where " + strings.Join(conds, " AND ")
	}
	return sql, args
}

// SelectGenTableListPaged 对应 selectGenTableList + startPage：过滤 + 分页（默认排序 table_id asc）。
func SelectGenTableListPaged(ctx context.Context, f GenTableListFilter, p Page) ([]*model.GenTable, int64, error) {
	sql, args := buildGenTableListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "table_id asc")
	if err != nil {
		return nil, 0, err
	}
	return scanGenTables(res.Rows), res.Total, nil
}

// SelectDbTableListPaged 对应 selectDbTableList：从 information_schema.tables 查可导入实表，排除 qrtz\_%/gen\_% 与已导入，
// 同过滤条件，order by create_time desc + 分页。仅回 table_name/table_comment/create_time/update_time。
func SelectDbTableListPaged(ctx context.Context, f GenTableListFilter, p Page) ([]*model.GenTable, int64, error) {
	base := "select table_name, table_comment, create_time, update_time from information_schema.tables " +
		"where table_schema = (select database()) AND table_name NOT LIKE 'qrtz\\_%' AND table_name NOT LIKE 'gen\\_%' " +
		"AND table_name NOT IN (select table_name from gen_table)"
	args := make([]interface{}, 0, 4)
	if f.TableName != "" {
		base += " AND lower(table_name) like lower(concat('%', ?, '%'))"
		args = append(args, f.TableName)
	}
	if f.TableComment != "" {
		base += " AND lower(table_comment) like lower(concat('%', ?, '%'))"
		args = append(args, f.TableComment)
	}
	if f.BeginTime != "" {
		base += " AND date_format(create_time,'%Y%m%d') >= date_format(?, '%Y%m%d')"
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		base += " AND date_format(create_time,'%Y%m%d') <= date_format(?, '%Y%m%d')"
		args = append(args, f.EndTime)
	}
	res, err := QueryPaged(ctx, p, base, args, "create_time desc")
	if err != nil {
		return nil, 0, err
	}
	return scanGenTables(res.Rows), res.Total, nil
}

// SelectDbTableListByNames 对应 selectDbTableListByNames：按表名数组批量反读实表（导入预览）。names 空则回空。
func SelectDbTableListByNames(ctx context.Context, names []string) ([]*model.GenTable, error) {
	if len(names) == 0 {
		return []*model.GenTable{}, nil
	}
	ph := make([]string, len(names))
	args := make([]interface{}, 0, len(names))
	for i, n := range names {
		ph[i] = "?"
		args = append(args, n)
	}
	sql := "select table_name, table_comment, create_time, update_time from information_schema.tables " +
		"where table_name NOT LIKE 'qrtz\\_%' and table_name NOT LIKE 'gen\\_%' and table_schema = (select database()) " +
		"and table_name in (" + strings.Join(ph, ",") + ")"
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanGenTables(recs), nil
}

// selectGenTableJoined 运行主表 LEFT JOIN 列的查询并取首个 GenTable（byId/byName 单表用）；无行回 nil。
func selectGenTableJoined(ctx context.Context, sql string, args ...interface{}) (*model.GenTable, error) {
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	list := assembleJoined(recs)
	if len(list) == 0 {
		return nil, nil
	}
	return list[0], nil
}

// SelectGenTableById 对应 selectGenTableById：主键取表（含 columns，order c.sort）；未命中回 nil。
func SelectGenTableById(ctx context.Context, id int64) (*model.GenTable, error) {
	return selectGenTableJoined(ctx, genTableJoinColumns+" where t.table_id = ? order by c.sort", id)
}

// SelectGenTableByName 对应 selectGenTableByName：按表名取表（含 columns）；未命中回 nil。
func SelectGenTableByName(ctx context.Context, name string) (*model.GenTable, error) {
	return selectGenTableJoined(ctx, genTableJoinColumns+" where t.table_name = ? order by c.sort", name)
}

// SelectGenTableAll 对应 selectGenTableAll：全表 + 各列（不含 gen_type/gen_path），按 c.sort；供编辑页 tables 回显。
func SelectGenTableAll(ctx context.Context) ([]*model.GenTable, error) {
	recs, err := g.DB().GetAll(ctx, genTableAllJoinColumns+" order by c.sort")
	if err != nil {
		return nil, err
	}
	return assembleJoined(recs), nil
}

// InsertGenTable 对应 insertGenTable：动态列仅非空、create_time=sysdate()、回写自增 tableId。
// 判空口径逐字对齐 XML：tableName 判 null（Go 以非空近似，前端编辑恒携带），其余判非空；formColNum 判 null（指针）。
func InsertGenTable(ctx context.Context, t *model.GenTable) (int64, error) {
	cols := make([]string, 0, 16)
	vals := make([]string, 0, 16)
	args := make([]interface{}, 0, 16)
	addStr := func(col, v string) {
		if v != "" {
			cols = append(cols, col)
			vals = append(vals, "?")
			args = append(args, v)
		}
	}
	addStr("table_name", t.TableName)
	addStr("table_comment", t.TableComment)
	addStr("class_name", t.ClassName)
	addStr("tpl_category", t.TplCategory)
	addStr("tpl_web_type", t.TplWebType)
	addStr("package_name", t.PackageName)
	addStr("module_name", t.ModuleName)
	addStr("business_name", t.BusinessName)
	addStr("function_name", t.FunctionName)
	addStr("function_author", t.FunctionAuthor)
	if t.FormColNum != nil {
		cols = append(cols, "form_col_num")
		vals = append(vals, "?")
		args = append(args, *t.FormColNum)
	}
	addStr("gen_type", t.GenType)
	addStr("gen_path", t.GenPath)
	addStr("remark", t.Remark)
	addStr("create_by", t.CreateBy)
	cols = append(cols, "create_time")
	vals = append(vals, "sysdate()")
	sql := "insert into gen_table(" + strings.Join(cols, ",") + ") values(" + strings.Join(vals, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	if t.TableId == 0 {
		if id, _ := res.LastInsertId(); id > 0 {
			t.TableId = id
		}
	}
	return t.TableId, nil
}

// UpdateGenTable 对应 updateGenTable：动态 SET 判空口径逐字对齐 XML（tableName/subTableName/subTableFkName/remark
// 判 null 允许置空串→本仓以「总是写入」近似其判 null 分支；其余判非空），update_time 强制 sysdate()。
func UpdateGenTable(ctx context.Context, t *model.GenTable) (int, error) {
	sets := make([]string, 0, 18)
	args := make([]interface{}, 0, 18)
	addStr := func(col, v string) {
		if v != "" {
			sets = append(sets, col+" = ?")
			args = append(args, v)
		}
	}
	addAlways := func(col, v string) {
		sets = append(sets, col+" = ?")
		args = append(args, v)
	}
	addAlways("table_name", t.TableName) // XML: tableName != null（编辑期恒传，Go 以总是写入近似）
	addStr("table_comment", t.TableComment)
	addAlways("sub_table_name", t.SubTableName)
	addAlways("sub_table_fk_name", t.SubTableFkName)
	addStr("class_name", t.ClassName)
	addStr("function_author", t.FunctionAuthor)
	if t.FormColNum != nil {
		sets = append(sets, "form_col_num = ?")
		args = append(args, *t.FormColNum)
	}
	addStr("gen_type", t.GenType)
	addStr("gen_path", t.GenPath)
	addStr("tpl_category", t.TplCategory)
	addStr("tpl_web_type", t.TplWebType)
	addStr("package_name", t.PackageName)
	addStr("module_name", t.ModuleName)
	addStr("business_name", t.BusinessName)
	addStr("function_name", t.FunctionName)
	addStr("options", t.Options)
	addStr("update_by", t.UpdateBy)
	addAlways("remark", t.Remark) // XML: remark != null（允许置空）
	sets = append(sets, "update_time = sysdate()")
	sql := "update gen_table set " + strings.Join(sets, ", ") + " where table_id = ?"
	args = append(args, t.TableId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// DeleteGenTableByIds 对应 deleteGenTableByIds：delete from gen_table where table_id in (...)。空集合守卫回 1=0。
func DeleteGenTableByIds(ctx context.Context, tableIds []int64) (int, error) {
	if len(tableIds) == 0 {
		_, err := g.DB().Exec(ctx, "delete from gen_table where 1 = 0")
		return 0, err
	}
	ph := make([]string, len(tableIds))
	args := make([]interface{}, 0, len(tableIds))
	for i, id := range tableIds {
		ph[i] = "?"
		args = append(args, id)
	}
	res, err := g.DB().Exec(ctx, "delete from gen_table where table_id in ("+strings.Join(ph, ",")+")", args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// CreateTableDDL 对应 <update id="createTable"> ${sql}：原样执行 DDL（由 service CreateSingleTable 校验后调用）。
// sql 已在 service 层严格校验为单条 CREATE TABLE，非用户裸串，故此处直接 Exec。
func CreateTableDDL(ctx context.Context, sql string) error {
	_, err := g.DB().Exec(ctx, sql)
	return err
}

// CheckTableExistCount 对应 checkTableExist：当前库同名实表数（>0 即存在）。参数化，无拼接注入。
func CheckTableExistCount(ctx context.Context, tableName string) (int, error) {
	v, err := g.DB().GetValue(ctx, "select count(1) from information_schema.tables where table_schema = "+
		"(select database()) and table_name = ?", tableName)
	if err != nil {
		return 0, err
	}
	return v.Int(), nil
}
