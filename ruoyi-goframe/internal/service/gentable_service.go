// Package service —— 代码生成业务编排，逐字对齐 GenTableServiceImpl + SqlUtil.filterKeyword。
//
// 与 Java 分层的差异（spec 决策，如实标注）：Go 无 Druid SQL parser，createTable 的「解析 CREATE 语句」
// 改由本文件的引号/注释感知的顶层 ';' 切分器复刻；service 只收集以 CREATE TABLE 起手的语句（非建表语句像
// Java 一样静默丢弃），并在执行前忠实复刻 filterKeyword（含其「先删净空白→带尾空格 token 永不命中」的怪癖，
// 不得修正）。行为契约对齐，AST 细节不复现（DDL 原样执行）。
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"

	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service/velocity"
)

// sqlKeywordRegex 逐字对齐 SqlUtil.SQL_REGEX（\u000B 即垂直制表符 Go 写作 \v）。
const sqlKeywordRegex = "\v|%0A|and |extractvalue|updatexml|sleep|information_schema|exec |insert |select |" +
	"delete |update |drop |count |chr |mid |master |truncate |char |declare |or |union |like |+|/*|user()"

// wsRe 复刻 Java replaceAll("\\p{Z}|\\s","")：删除 Unicode 分隔符类(\p{Z})与 \s。
var wsRe = regexp.MustCompile(`[\p{Z}\s]`)

// FilterKeyword 忠实复刻 SqlUtil.filterKeyword：
//  1. 入参空（isEmpty）→ 直接返回（放行）；
//  2. normalized = 删净所有空白；
//  3. 按字符集 {\,|} 拆分 SQL_REGEX（langSplit 语义，丢空 token）；
//  4. 任一 token 在 normalized 上 indexOfIgnoreCase 命中(>-1) → 抛「包含敏感关键词」。
//
// ⚠️ 怪癖（不得修正）：因第 2 步删净空白，第 3 步得到的「带尾空格」token（"and "/"select "/"drop " 等）
// 在 normalized 上永不命中，真正生效子集仅 extractvalue|updatexml|sleep|information_schema|+|/*|user()|
// %0A（\u000B 被自身删除、and/exec 等带空格者失效）。一条正常 CREATE TABLE 恒放行。此处原样复刻以保行为一致。
func FilterKeyword(value string) error {
	if value == "" {
		return nil
	}
	normalized := wsRe.ReplaceAllString(value, "")
	for _, token := range langSplit(sqlKeywordRegex, "\\|") {
		if indexOfIgnoreCase(normalized, token) > -1 {
			return fmt.Errorf("请求参数包含敏感关键词'%s'，可能存在安全风险", token)
		}
	}
	return nil
}

// indexOfIgnoreCase 复刻 StringUtils.indexOfIgnoreCase：忽略大小写取首现下标，未命中 -1（空 needle→0）。
func indexOfIgnoreCase(str, sub string) int {
	return strings.Index(strings.ToLower(str), strings.ToLower(sub))
}

// createTableRe 判语句是否 CREATE TABLE 起手（允许前置注释由调用方剥离后再传入）。
var createTableRe = regexp.MustCompile(`(?i)^\s*create\s+table\b`)

// ExtractCreateStatements 切分 sql 为顶层语句并仅收集 CREATE TABLE 者（静默丢弃非建表语句，对齐 controller）。
// 切分感知单/双引号、反引号、--/#/行注释、/* */ 块注释、括号深度，仅在深度 0 的 ';' 处断开。
func ExtractCreateStatements(sql string) []string {
	out := make([]string, 0, 4)
	for _, stmt := range splitTopLevelStatements(sql) {
		trimmed := stripLeadingComments(stmt)
		if trimmed == "" {
			continue
		}
		if createTableRe.MatchString(trimmed) {
			out = append(out, strings.TrimSpace(stmt))
		}
	}
	return out
}

// splitTopLevelStatements 按顶层 ';' 切分（引号/注释/括号感知）；返回各语句原文（未 trim，可能含尾空白）。
func splitTopLevelStatements(sql string) []string {
	stmts := make([]string, 0, 4)
	var cur strings.Builder
	runes := []rune(sql)
	n := len(runes)
	depth := 0
	var quote rune // 当前所在引号：' " 或 `（0 表示不在引号内）
	for i := 0; i < n; i++ {
		c := runes[i]
		switch {
		case quote != 0:
			// 引号内：处理反斜杠转义（非反引号）与结束引号
			if c == '\\' && quote != '`' && i+1 < n {
				cur.WriteRune(c)
				cur.WriteRune(runes[i+1])
				i++
				continue
			}
			cur.WriteRune(c)
			if c == quote {
				quote = 0
			}
		case c == '\'' || c == '"' || c == '`':
			quote = c
			cur.WriteRune(c)
		case c == '/' && i+1 < n && runes[i+1] == '*':
			cur.WriteRune(c)
			cur.WriteRune(runes[i+1])
			i++
			for i++; i < n; i++ {
				cur.WriteRune(runes[i])
				if runes[i] == '*' && i+1 < n && runes[i+1] == '/' {
					cur.WriteRune(runes[i+1])
					i++
					break
				}
			}
		case (c == '-' && i+1 < n && runes[i+1] == '-') || c == '#':
			for i < n && runes[i] != '\n' {
				cur.WriteRune(runes[i])
				i++
			}
			if i < n {
				cur.WriteRune(runes[i]) // 保留换行
			}
		case c == '(':
			depth++
			cur.WriteRune(c)
		case c == ')':
			if depth > 0 {
				depth--
			}
			cur.WriteRune(c)
		case c == ';' && depth == 0:
			stmts = append(stmts, cur.String())
			cur.Reset()
		default:
			cur.WriteRune(c)
		}
	}
	if strings.TrimSpace(cur.String()) != "" {
		stmts = append(stmts, cur.String())
	}
	return stmts
}

// stripLeadingComments 去掉语句前导的空白/注释，用于判定起始关键字（不影响执行原文）。
func stripLeadingComments(s string) string {
	res := strings.TrimSpace(s)
	for {
		if strings.HasPrefix(res, "--") || strings.HasPrefix(res, "#") {
			nl := strings.IndexByte(res, '\n')
			if nl < 0 {
				return ""
			}
			res = strings.TrimSpace(res[nl+1:])
			continue
		}
		if strings.HasPrefix(res, "/*") {
			end := strings.Index(res, "*/")
			if end < 0 {
				return ""
			}
			res = strings.TrimSpace(res[end+2:])
			continue
		}
		break
	}
	return res
}

// createTableNameRe 从 CREATE TABLE 语句提取表名（去 IF NOT EXISTS，取下一标识符，容忍 db.table 与反引号）。
var createTableNameRe = regexp.MustCompile(`(?is)^\s*create\s+table\s+(?:if\s+not\s+exists\s+)?([^\s(]+)`)

// TableNameFromCreate 复刻 controller 的 getTableName().replaceAll("`","")：取建表目标表名（去反引号、去 db. 前缀）。
func TableNameFromCreate(stmt string) string {
	m := createTableNameRe.FindStringSubmatch(strings.TrimSpace(stmt))
	if m == nil {
		return ""
	}
	name := strings.ReplaceAll(m[1], "`", "")
	if idx := strings.LastIndex(name, "."); idx >= 0 {
		name = name[idx+1:]
	}
	return name
}

// CreateSingleTable 对齐 GenTableServiceImpl.createTable：独立纵深防御——先 filterKeyword，再要求入参恰为
// 单条 CREATE TABLE（多语句/非建表→拒），校验通过原样执行。返回是否成功。
func CreateSingleTable(ctx context.Context, sql string) (bool, error) {
	if err := FilterKeyword(sql); err != nil {
		return false, err
	}
	stmts := ExtractCreateStatements(sql)
	if len(stmts) != 1 || stripLeadingComments(sql) == "" {
		return false, fmt.Errorf("非法建表语句：仅允许执行单条 CREATE TABLE")
	}
	if err := CreateTableDDL(ctx, sql); err != nil {
		return false, err
	}
	return true, nil
}

// CheckTableExist 对齐 checkTableExist：count>0 即存在。
func CheckTableExist(ctx context.Context, tableName string) (bool, error) {
	cnt, err := CheckTableExistCount(ctx, tableName)
	if err != nil {
		return false, err
	}
	return cnt > 0, nil
}

// GetGenTableById 对齐 service.selectGenTableById：查库→nil 抛「生成表不存在，ID：x」→setTableFromOptions。
func GetGenTableById(ctx context.Context, id int64) (*model.GenTable, error) {
	t, err := SelectGenTableById(ctx, id)
	if err != nil {
		return nil, err
	}
	if t == nil {
		return nil, fmt.Errorf("生成表不存在，ID：%d", id)
	}
	setTableFromOptions(t)
	return t, nil
}

// RequireTableByName 对齐 private requireTableByName：按名查表，nil 抛「表不存在或尚未导入：x」。
func RequireTableByName(ctx context.Context, tableName string) (*model.GenTable, error) {
	t, err := SelectGenTableByName(ctx, tableName)
	if err != nil {
		return nil, err
	}
	if t == nil {
		return nil, fmt.Errorf("表不存在或尚未导入：%s", tableName)
	}
	return t, nil
}

// setTableFromOptions 对齐 setTableFromOptions：解析 options JSON 回填 treeCode/parentMenuId/view 等瞬态。
// options 为空/非法（Java parseObject 回 null）→ 原样跳过。
func setTableFromOptions(t *model.GenTable) {
	if strings.TrimSpace(t.Options) == "" {
		return
	}
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(t.Options), &obj); err != nil || obj == nil {
		return
	}
	t.TreeCode = jsonStr(obj, model.TreeCode)
	t.TreeParentCode = jsonStr(obj, model.TreeParentCode)
	t.TreeName = jsonStr(obj, model.TreeName)
	t.ParentMenuId = jsonInt64(obj, model.ParentMenuId)
	t.ParentMenuName = jsonStr(obj, model.ParentMenuName)
	t.View = jsonBool(obj, model.GenView)
}

// EditGenTable 对齐 updateGenTable：params→options JSON → 更新主表 → 行成功且带 columns 时逐列归属校验后更新（事务）。
func EditGenTable(ctx context.Context, t *model.GenTable) error {
	params := t.Params
	if params == nil {
		params = map[string]interface{}{}
	}
	options, err := json.Marshal(params)
	if err != nil {
		return err
	}
	t.Options = string(options)
	return g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		row, err := UpdateGenTable(ctx, t)
		if err != nil {
			return err
		}
		if row > 0 && len(t.Columns) > 0 {
			for _, c := range t.Columns {
				if c.TableId == 0 || c.TableId != t.TableId {
					return fmt.Errorf("列[%s]不属于当前生成表，禁止跨表修改", c.ColumnName)
				}
				if _, err := UpdateGenTableColumn(ctx, c); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

// RemoveGenTables 对齐 deleteGenTableByIds：删主表 + 级联删列（事务）。
func RemoveGenTables(ctx context.Context, tableIds []int64) error {
	return g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := DeleteGenTableByIds(ctx, tableIds); err != nil {
			return err
		}
		return DeleteGenTableColumnByTableIds(ctx, tableIds)
	})
}

// ImportGenTable 对齐 importGenTable：一次批量反读列按表名分组→逐表 InitTable+insert→rows>0 则列 InitColumnField+batchInsert。
// 整体 @Transactional；异常包装「导入失败：x」（对齐 Java catch）。
func ImportGenTable(ctx context.Context, tableList []*model.GenTable, tplWebType, operName string) error {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		names := make([]string, 0, len(tableList))
		for _, t := range tableList {
			names = append(names, t.TableName)
		}
		var allCols []*model.GenTableColumn
		if len(names) > 0 {
			cols, err := SelectDbTableColumnsByNames(ctx, names)
			if err != nil {
				return err
			}
			allCols = cols
		}
		columnsByTable := make(map[string][]*model.GenTableColumn)
		for _, c := range allCols {
			columnsByTable[c.TableName] = append(columnsByTable[c.TableName], c)
		}
		for _, table := range tableList {
			tableName := table.TableName
			table.TplWebType = tplWebType
			InitTable(ctx, table, operName)
			row, err := InsertGenTable(ctx, table)
			if err != nil {
				return err
			}
			if row > 0 {
				cols := columnsByTable[tableName]
				if len(cols) > 0 {
					for _, c := range cols {
						InitColumnField(c, table)
					}
					if err := BatchInsertGenTableColumn(ctx, cols); err != nil {
						return err
					}
				}
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("导入失败：%s", err.Error())
	}
	return nil
}

// SynchDb 对齐 synchDb：以实表为准增量 diff（新增列 insert、已存在列沿用旧 columnId 转 update 并保留人工配置、
// 实表已删列按 column_id 精删）。整体 @Transactional。文案逐字对齐 Java。
func SynchDb(ctx context.Context, tableName string) error {
	table, err := RequireTableByName(ctx, tableName)
	if err != nil {
		return err
	}
	tableColumnMap := make(map[string]*model.GenTableColumn)
	for _, c := range table.Columns {
		tableColumnMap[c.ColumnName] = c
	}
	dbTableColumns, err := SelectDbTableColumnsByName(ctx, tableName)
	if err != nil {
		return err
	}
	if len(dbTableColumns) == 0 {
		return fmt.Errorf("同步数据失败，原表结构不存在")
	}
	dbNames := make(map[string]bool, len(dbTableColumns))
	for _, c := range dbTableColumns {
		dbNames[c.ColumnName] = true
	}
	return g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		for _, column := range dbTableColumns {
			InitColumnField(column, table)
			if prev, ok := tableColumnMap[column.ColumnName]; ok {
				column.ColumnId = prev.ColumnId
				if column.IsListFlag() {
					column.DictType = prev.DictType
					column.QueryType = prev.QueryType
				}
				if prev.IsRequired != "" && !column.IsPkFlag() &&
					(column.IsInsertFlag() || column.IsEditFlag()) &&
					(column.IsUsableColumn() || !column.IsSuperColumn()) {
					column.IsRequired = prev.IsRequired
					column.HtmlType = prev.HtmlType
				}
				if _, err := UpdateGenTableColumn(ctx, column); err != nil {
					return err
				}
			} else {
				if _, err := InsertGenTableColumn(ctx, column); err != nil {
					return err
				}
			}
		}
		delIds := make([]int64, 0)
		for _, c := range table.Columns {
			if !dbNames[c.ColumnName] {
				delIds = append(delIds, c.ColumnId)
			}
		}
		if len(delIds) > 0 {
			return DeleteGenTableColumns(ctx, delIds)
		}
		return nil
	})
}

// ValidateEdit 对齐 validateEdit：tree→treeCode/treeParentCode/treeName 非空；sub→subTableName/subTableFkName 非空。
// Go 直接读 t.Params（等价 Java 读 options=json(params) 后取键）。
func ValidateEdit(t *model.GenTable) error {
	if t.TplCategory == model.TplTree {
		if jsonStr(t.Params, model.TreeCode) == "" {
			return fmt.Errorf("树编码字段不能为空")
		} else if jsonStr(t.Params, model.TreeParentCode) == "" {
			return fmt.Errorf("树父编码字段不能为空")
		} else if jsonStr(t.Params, model.TreeName) == "" {
			return fmt.Errorf("树名称字段不能为空")
		}
	} else if t.TplCategory == model.TplSub {
		if t.SubTableName == "" {
			return fmt.Errorf("关联子表的表名不能为空")
		} else if t.SubTableFkName == "" {
			return fmt.Errorf("子表关联的外键名不能为空")
		}
	}
	return nil
}

// ---- options/params JSON 取值小工具（对齐 fastjson getString/getLongValue/getBooleanValue 的宽松语义）----

func jsonStr(m map[string]interface{}, key string) string {
	if m == nil {
		return ""
	}
	if v, ok := m[key]; ok && v != nil {
		if s, ok := v.(string); ok {
			return s
		}
		return fmt.Sprintf("%v", v)
	}
	return ""
}

func jsonInt64(m map[string]interface{}, key string) int64 {
	if m == nil {
		return 0
	}
	switch v := m[key].(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	default:
		return 0
	}
}

func jsonBool(m map[string]interface{}, key string) bool {
	if m == nil {
		return false
	}
	if v, ok := m[key].(bool); ok {
		return v
	}
	return false
}

// ---- 代码预览（previewCode，对齐 GenTableServiceImpl.previewCode + GenController.preview）----

// PreviewResult 保序的「模板路径→生成内容」映射，序列化时按插入顺序输出 JSON 对象
// （逐字对齐 Java LinkedHashMap：键序 = VelocityUtils.getTemplateList 顺序）。
type PreviewResult struct {
	keys []string
	vals map[string]string
}

func newPreviewResult() *PreviewResult {
	return &PreviewResult{vals: map[string]string{}}
}

func (p *PreviewResult) put(key, val string) {
	if _, ok := p.vals[key]; !ok {
		p.keys = append(p.keys, key)
	}
	p.vals[key] = val
}

// MarshalJSON 按 keys 顺序输出对象；值用 encoding/json 转义（\r\n、中文、引号等）。
func (p *PreviewResult) MarshalJSON() ([]byte, error) {
	var sb strings.Builder
	sb.WriteByte('{')
	for i, k := range p.keys {
		if i > 0 {
			sb.WriteByte(',')
		}
		kb, _ := json.Marshal(k)
		vb, _ := json.Marshal(p.vals[k])
		sb.Write(kb)
		sb.WriteByte(':')
		sb.Write(vb)
	}
	sb.WriteByte('}')
	return []byte(sb.String()), nil
}

// SetSubTable 对齐 setSubTable：subTableName 非空则按名反读子表（含列），未导入即抛。
func SetSubTable(ctx context.Context, table *model.GenTable) error {
	subTableName := table.SubTableName
	if subTableName != "" {
		subTable, err := SelectGenTableByName(ctx, subTableName)
		if err != nil {
			return err
		}
		if subTable == nil {
			return fmt.Errorf("关联子表不存在或尚未导入：%s", subTableName)
		}
		setTableFromOptions(subTable)
		table.SubTable = subTable
	}
	return nil
}

// SetPkColumn 对齐 setPkColumn：首个 isPk 列，否则降级取首列；子表同理。文案逐字对齐 Java。
func SetPkColumn(table *model.GenTable) error {
	if len(table.Columns) == 0 {
		return fmt.Errorf("表未配置任何列，无法生成：%s", table.TableName)
	}
	table.PkColumn = nil
	for _, column := range table.Columns {
		if column.IsPkFlag() {
			table.PkColumn = column
			break
		}
	}
	if table.PkColumn == nil {
		table.PkColumn = table.Columns[0]
	}
	if table.TplCategory == model.TplSub {
		sub := table.SubTable
		if sub == nil || len(sub.Columns) == 0 {
			return fmt.Errorf("主子表模板要求子表已导入且含列配置，请检查子表：%s", table.SubTableName)
		}
		sub.PkColumn = nil
		for _, column := range sub.Columns {
			if column.IsPkFlag() {
				sub.PkColumn = column
				break
			}
		}
		if sub.PkColumn == nil {
			sub.PkColumn = sub.Columns[0]
		}
	}
	return nil
}

// PreviewCode 逐字对齐 previewCode：查表→装配子表→定主键→prepareContext→getTemplateList→逐模板渲染。
// 不落盘；datetime 用当日（生产对齐 DateUtils.getDate()，不覆盖）。返回保序结果供 controller 直出。
func PreviewCode(ctx context.Context, tableId int64) (*PreviewResult, error) {
	table, err := GetGenTableById(ctx, tableId) // 内含「生成表不存在，ID：x」抛出
	if err != nil {
		return nil, err
	}
	if err := SetSubTable(ctx, table); err != nil {
		return nil, err
	}
	if err := SetPkColumn(table); err != nil {
		return nil, err
	}
	ctxMap := velocity.PrepareContext(table)
	res := newPreviewResult()
	for _, tpl := range velocity.GetTemplateList(table) {
		content, err := velocity.RenderTemplate(tpl, ctxMap)
		if err != nil {
			return nil, err
		}
		res.put(tpl, content)
	}
	return res, nil
}
