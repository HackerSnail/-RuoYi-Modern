// Package service —— 代码生成列 mapper，逐条对齐 GenTableColumnMapper.xml 的语义。
//
// 判空口径说明（null≈空串约定，与 batch 15 同款）：Go string 无法区分 Java 的 null 与 ""。
//   - insert/batchInsert：XML 各字段判 `!= null and != ''`（batchInsert 用固定列、未设为 NULL，注释已言
//     下游用 isNotEmpty 判定 NULL≈'' 等价），故本仓以「空串跳过 insert 列 / batchInsert 写空串」近似，行为等价。
//   - updateGenTableColumn：XML 判 `!= null` 以保留 '0'；本仓对 is_* 用「非空即写」——因这些值来自
//     information_schema CASE 恒为 '0'/'1'（非空）或 initColumnField 置 '1'，唯二 null 场景（未设置）
//     在 Go 为空串→跳过，与 Java 一致；仅 columnComment 空注释 Java 会写 ''、本仓跳过（唯一细微差异，
//     影响可忽略：下次同步会带回真实注释）。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectGenTableColumnVo 对齐 <sql id="selectGenTableColumnVo">（22 列）。
const selectGenTableColumnVo = "select column_id, table_id, column_name, column_comment, column_type, " +
	"java_type, java_field, is_pk, is_increment, is_required, is_insert, is_edit, is_list, is_query, " +
	"query_type, html_type, dict_type, sort, create_by, create_time, update_by, update_time from gen_table_column"

func scanGenTableColumns(recs gdb.Result) []*model.GenTableColumn {
	out := make([]*model.GenTableColumn, 0, len(recs))
	for _, r := range recs {
		var c model.GenTableColumn
		if gconv.Struct(r.Map(), &c) == nil {
			out = append(out, &c)
		}
	}
	return out
}

// SelectGenTableColumnListByTableId 对应 selectGenTableColumnListByTableId：where table_id=? order by sort。
func SelectGenTableColumnListByTableId(ctx context.Context, tableId int64) ([]*model.GenTableColumn, error) {
	recs, err := g.DB().GetAll(ctx, selectGenTableColumnVo+" where table_id = ? order by sort", tableId)
	if err != nil {
		return nil, err
	}
	return scanGenTableColumns(recs), nil
}

// dbTableColumnsCase 对齐 selectDbTableColumns 的 CASE 推导列（不含 table_name 分组键）。
const dbTableColumnsCase = "select column_name, (case when (is_nullable = 'no' && column_key != 'PRI') then '1' " +
	"else '0' end) as is_required, (case when column_key = 'PRI' then '1' else '0' end) as is_pk, " +
	"ordinal_position as sort, column_comment, (case when extra = 'auto_increment' then '1' else '0' end) " +
	"as is_increment, column_type from information_schema.columns where table_schema = (select database())"

// SelectDbTableColumnsByName 对应 selectDbTableColumnsByName：单表实列反读，按 ordinal_position 排序。
func SelectDbTableColumnsByName(ctx context.Context, tableName string) ([]*model.GenTableColumn, error) {
	sql := dbTableColumnsCase + " and table_name = (?) order by ordinal_position"
	recs, err := g.DB().GetAll(ctx, sql, tableName)
	if err != nil {
		return nil, err
	}
	return scanGenTableColumns(recs), nil
}

// SelectDbTableColumnsByNames 对应 selectDbTableColumnsByNames：多表批量反读（带 table_name 分组键）；
// names 空时退化 1=0（避免 IN () 语法错、不跨库泄露）。order by table_name, ordinal_position。
func SelectDbTableColumnsByNames(ctx context.Context, names []string) ([]*model.GenTableColumn, error) {
	sql := "select table_name, column_name, (case when (is_nullable = 'no' && column_key != 'PRI') then '1' " +
		"else '0' end) as is_required, (case when column_key = 'PRI' then '1' else '0' end) as is_pk, " +
		"ordinal_position as sort, column_comment, (case when extra = 'auto_increment' then '1' else '0' end) " +
		"as is_increment, column_type from information_schema.columns where table_schema = (select database())"
	args := make([]interface{}, 0, len(names))
	if len(names) > 0 {
		ph := make([]string, len(names))
		for i, n := range names {
			ph[i] = "?"
			args = append(args, n)
		}
		sql += " and table_name in (" + strings.Join(ph, ",") + ")"
	} else {
		sql += " and 1 = 0"
	}
	sql += " order by table_name, ordinal_position"
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanGenTableColumns(recs), nil
}

// InsertGenTableColumn 对应 insertGenTableColumn：动态列仅非空、create_time=sysdate()、回写自增 columnId。
func InsertGenTableColumn(ctx context.Context, c *model.GenTableColumn) (int64, error) {
	cols := make([]string, 0, 19)
	vals := make([]string, 0, 19)
	args := make([]interface{}, 0, 19)
	addStr := func(col, v string) {
		if v != "" {
			cols = append(cols, col)
			vals = append(vals, "?")
			args = append(args, v)
		}
	}
	if c.TableId != 0 {
		cols = append(cols, "table_id")
		vals = append(vals, "?")
		args = append(args, c.TableId)
	}
	addStr("column_name", c.ColumnName)
	addStr("column_comment", c.ColumnComment)
	addStr("column_type", c.ColumnType)
	addStr("java_type", c.JavaType)
	addStr("java_field", c.JavaField)
	addStr("is_pk", c.IsPk)
	addStr("is_increment", c.IsIncrement)
	addStr("is_required", c.IsRequired)
	addStr("is_insert", c.IsInsert)
	addStr("is_edit", c.IsEdit)
	addStr("is_list", c.IsList)
	addStr("is_query", c.IsQuery)
	addStr("query_type", c.QueryType)
	addStr("html_type", c.HtmlType)
	addStr("dict_type", c.DictType)
	if c.Sort != nil {
		cols = append(cols, "sort")
		vals = append(vals, "?")
		args = append(args, *c.Sort)
	}
	addStr("create_by", c.CreateBy)
	cols = append(cols, "create_time")
	vals = append(vals, "sysdate()")
	sql := "insert into gen_table_column(" + strings.Join(cols, ",") + ") values(" + strings.Join(vals, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	if c.ColumnId == 0 {
		if id, _ := res.LastInsertId(); id > 0 {
			c.ColumnId = id
		}
	}
	return c.ColumnId, nil
}

// BatchInsertGenTableColumn 对应 batchInsertGenTableColumn：固定 18 列多值 INSERT，create_time=sysdate()。
// 未设置字段写空串（Java 写 NULL，下游 isNotEmpty 判定二者等价）；sort 为 nil 写 NULL。
func BatchInsertGenTableColumn(ctx context.Context, columns []*model.GenTableColumn) error {
	if len(columns) == 0 {
		return nil
	}
	colDefs := "table_id, column_name, column_comment, column_type, java_type, java_field, is_pk, is_increment, " +
		"is_required, is_insert, is_edit, is_list, is_query, query_type, html_type, dict_type, sort, create_by, create_time"
	var sb strings.Builder
	sb.WriteString("insert into gen_table_column (")
	sb.WriteString(colDefs)
	sb.WriteString(") values ")
	args := make([]interface{}, 0, len(columns)*18)
	for i, c := range columns {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, sysdate())")
		var sortArg interface{}
		if c.Sort != nil {
			sortArg = *c.Sort
		}
		args = append(args, c.TableId, c.ColumnName, c.ColumnComment, c.ColumnType, c.JavaType, c.JavaField,
			c.IsPk, c.IsIncrement, c.IsRequired, c.IsInsert, c.IsEdit, c.IsList, c.IsQuery, c.QueryType,
			c.HtmlType, c.DictType, sortArg, c.CreateBy)
	}
	_, err := g.DB().Exec(ctx, sb.String(), args...)
	return err
}

// UpdateGenTableColumn 对应 updateGenTableColumn：见文件头 null≈空串约定。update_time 强制 sysdate()。
func UpdateGenTableColumn(ctx context.Context, c *model.GenTableColumn) (int, error) {
	sets := make([]string, 0, 14)
	args := make([]interface{}, 0, 14)
	addStr := func(col, v string) {
		if v != "" {
			sets = append(sets, col+" = ?")
			args = append(args, v)
		}
	}
	addStr("column_comment", c.ColumnComment)
	addStr("java_type", c.JavaType)
	addStr("column_type", c.ColumnType)
	addStr("java_field", c.JavaField)
	addStr("is_insert", c.IsInsert)
	addStr("is_edit", c.IsEdit)
	addStr("is_list", c.IsList)
	addStr("is_query", c.IsQuery)
	addStr("is_required", c.IsRequired)
	addStr("query_type", c.QueryType)
	addStr("html_type", c.HtmlType)
	addStr("dict_type", c.DictType)
	if c.Sort != nil {
		sets = append(sets, "sort = ?")
		args = append(args, *c.Sort)
	}
	addStr("update_by", c.UpdateBy)
	sets = append(sets, "update_time = sysdate()")
	sql := "update gen_table_column set " + strings.Join(sets, ", ") + " where column_id = ?"
	args = append(args, c.ColumnId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// DeleteGenTableColumnByTableIds 对应 deleteGenTableColumnByTableIds：table_ids 空退化 1=0（不全表删）。
func DeleteGenTableColumnByTableIds(ctx context.Context, tableIds []int64) error {
	if len(tableIds) == 0 {
		_, err := g.DB().Exec(ctx, "delete from gen_table_column where 1 = 0")
		return err
	}
	ph := make([]string, len(tableIds))
	args := make([]interface{}, 0, len(tableIds))
	for i, id := range tableIds {
		ph[i] = "?"
		args = append(args, id)
	}
	_, err := g.DB().Exec(ctx, "delete from gen_table_column where table_id in ("+strings.Join(ph, ",")+")", args...)
	return err
}

// DeleteGenTableColumns 对应 deleteGenTableColumns：按 column_id 精删；column_ids 空退化 1=0。
func DeleteGenTableColumns(ctx context.Context, columnIds []int64) error {
	if len(columnIds) == 0 {
		_, err := g.DB().Exec(ctx, "delete from gen_table_column where 1 = 0")
		return err
	}
	ph := make([]string, len(columnIds))
	args := make([]interface{}, 0, len(columnIds))
	for i, id := range columnIds {
		ph[i] = "?"
		args = append(args, id)
	}
	_, err := g.DB().Exec(ctx, "delete from gen_table_column where column_id in ("+strings.Join(ph, ",")+")", args...)
	return err
}
