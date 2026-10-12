package service

import (
	"strings"
	"testing"

	"github.com/gogf/gf/v2/encoding/gjson"

	"ruoyi-goframe/internal/model"
)

// TestPreviewResultOrder 锁定：*PreviewResult 的保序 MarshalJSON 在被 GoFrame gjson（WriteJson 的编码路径）
// 包裹于 map[string]interface{} 内时仍按插入顺序输出对象键（对齐 Java LinkedHashMap）。
func TestPreviewResultOrder(t *testing.T) {
	p := newPreviewResult()
	p.put("vm/java/domain.java.vm", "a\r\nb")
	p.put("vm/java/service.java.vm", "x")
	body := map[string]interface{}{"code": 200, "data": p}
	enc, _ := gjson.Encode(body)
	out := string(enc)
	// 键序必须 domain 先于 service；且值含 \r\n 转义。
	if i, j := strings.Index(out, "domain.java.vm"), strings.Index(out, "service.java.vm"); !(i >= 0 && j >= 0 && i < j) {
		t.Fatalf("键序未保真: %s", out)
	}
	if !strings.Contains(out, `a\r\nb`) {
		t.Fatalf("CRLF 未正确转义: %s", out)
	}
}

// TestFilterKeyword 锁定 SqlUtil.filterKeyword 的怪癖行为（不得修正）：
// 先删净空白 → 带尾空格 token（"select "/"drop " 等）永不命中，仅无空格子集真正生效。
func TestFilterKeyword(t *testing.T) {
	cases := []struct {
		sql     string
		wantErr bool
		note    string
	}{
		{"", false, "空入参放行"},
		{"CREATE TABLE t_demo (id bigint, name varchar(50))", false, "正常建表放行"},
		{"select * from users", false, "怪癖：'select ' 带尾空格，删空白后不命中→放行"},
		{"DROP TABLE users", false, "怪癖：'drop ' 带尾空格→放行"},
		{"x extractvalue(y)", true, "无空格 token extractvalue 命中"},
		{"select information_schema.tables", true, "information_schema 命中"},
		{"sleep(5)", true, "sleep 命中"},
		{"a+b", true, "'+' 命中"},
		{"/* c */", true, "'/*' 命中"},
		{"call user()", true, "user() 命中"},
	}
	for _, c := range cases {
		err := FilterKeyword(c.sql)
		if (err != nil) != c.wantErr {
			t.Errorf("%q(%s): err=%v wantErr=%v", c.sql, c.note, err, c.wantErr)
		}
	}
}

// TestExtractCreateStatements 校顶层 ';' 切分器仅收集 CREATE TABLE、静默丢弃非建表语句、引号内分号不切。
func TestExtractCreateStatements(t *testing.T) {
	cases := []struct {
		sql     string
		wantLen int
		note    string
	}{
		{"CREATE TABLE a (id int); DROP TABLE b;", 1, "仅收集 CREATE"},
		{"-- comment\nCREATE TABLE x (id int);", 1, "前导注释不影响"},
		{"UPDATE t SET a=1;", 0, "非建表全部丢弃"},
		{"CREATE TABLE a (b varchar(10) DEFAULT ';');", 1, "引号内分号不切分"},
		{"CREATE TABLE a (id int); CREATE TABLE b (id int);", 2, "多条建表各收一条"},
	}
	for _, c := range cases {
		got := ExtractCreateStatements(c.sql)
		if len(got) != c.wantLen {
			t.Errorf("%q(%s): len=%d want %d", c.sql, c.note, len(got), c.wantLen)
		}
	}
}

// TestTableNameFromCreate 校表名提取：去反引号、去 IF NOT EXISTS、去 db. 前缀。
func TestTableNameFromCreate(t *testing.T) {
	cases := []struct{ stmt, want string }{
		{"CREATE TABLE `sys_foo` (id int)", "sys_foo"},
		{"CREATE TABLE IF NOT EXISTS db.bar (id int)", "bar"},
		{"create table biz_demo(id int)", "biz_demo"},
	}
	for _, c := range cases {
		if got := TableNameFromCreate(c.stmt); got != c.want {
			t.Errorf("%q: got=%q want=%q", c.stmt, got, c.want)
		}
	}
}

// TestValidateEdit 校 validateEdit：tree/sub 必填项，crud 无约束。
func TestValidateEdit(t *testing.T) {
	// crud 恒通过
	if err := ValidateEdit(&model.GenTable{TplCategory: model.TplCrud}); err != nil {
		t.Errorf("crud should pass, got %v", err)
	}
	// tree 缺 treeCode
	err := ValidateEdit(&model.GenTable{TplCategory: model.TplTree, Params: map[string]interface{}{}})
	if err == nil || !strings.Contains(err.Error(), "树编码字段") {
		t.Errorf("tree missing treeCode should err, got %v", err)
	}
	// tree 全填
	treeOK := &model.GenTable{TplCategory: model.TplTree, Params: map[string]interface{}{
		model.TreeCode: "dept_id", model.TreeParentCode: "parent_id", model.TreeName: "dept_name",
	}}
	if err := ValidateEdit(treeOK); err != nil {
		t.Errorf("tree full should pass, got %v", err)
	}
	// sub 缺子表名
	if err := ValidateEdit(&model.GenTable{TplCategory: model.TplSub}); err == nil {
		t.Errorf("sub missing subTableName should err")
	}
	// sub 缺外键名
	subFk := &model.GenTable{TplCategory: model.TplSub, SubTableName: "sys_user"}
	if err := ValidateEdit(subFk); err == nil || !strings.Contains(err.Error(), "外键名") {
		t.Errorf("sub missing fk should err, got %v", err)
	}
	// sub 全填
	subOK := &model.GenTable{TplCategory: model.TplSub, SubTableName: "sys_user", SubTableFkName: "user_id"}
	if err := ValidateEdit(subOK); err != nil {
		t.Errorf("sub full should pass, got %v", err)
	}
}

// TestInitColumnField 校列字段推导：类型→javaType/htmlType、列名后缀→queryType/控件、is_* 依名单与主键。
func TestInitColumnField(t *testing.T) {
	tbl := &model.GenTable{TableId: 7, CreateBy: "admin"}

	// 普通字符串列：*name → LIKE，全部 is_* 置位
	c1 := &model.GenTableColumn{ColumnName: "user_name", ColumnType: "varchar(50)", IsPk: "0"}
	InitColumnField(c1, tbl)
	if c1.JavaField != "userName" || c1.JavaType != model.TypeString ||
		c1.HtmlType != model.HtmlInput || c1.QueryType != model.QueryLike ||
		c1.IsInsert != model.Require || c1.IsEdit != model.Require ||
		c1.IsList != model.Require || c1.IsQuery != model.Require {
		t.Errorf("user_name: %+v", c1)
	}

	// 主键 bigint(20)：不置 edit/list/query，javaType=Long，queryType=EQ
	c2 := &model.GenTableColumn{ColumnName: "id", ColumnType: "bigint(20)", IsPk: "1"}
	InitColumnField(c2, tbl)
	if c2.JavaType != model.TypeLong || c2.IsEdit != "" || c2.IsList != "" || c2.IsQuery != "" ||
		c2.QueryType != model.QueryEq {
		t.Errorf("id pk: %+v", c2)
	}

	// 时间列 datetime：Date/Datetime；create_time 在 NotList/NotQuery/NotEdit
	c3 := &model.GenTableColumn{ColumnName: "create_time", ColumnType: "datetime", IsPk: "0"}
	InitColumnField(c3, tbl)
	if c3.JavaType != model.TypeDate || c3.HtmlType != model.HtmlDatetime ||
		c3.IsEdit != "" || c3.IsList != "" || c3.IsQuery != "" {
		t.Errorf("create_time: %+v", c3)
	}

	// 富文本 content longtext：textarea 被 *content 后缀覆盖为 editor
	c4 := &model.GenTableColumn{ColumnName: "notice_content", ColumnType: "longtext", IsPk: "0"}
	InitColumnField(c4, tbl)
	if c4.HtmlType != model.HtmlEditor {
		t.Errorf("notice_content htmlType=%q want editor", c4.HtmlType)
	}

	// decimal(10,2) → BigDecimal
	c5 := &model.GenTableColumn{ColumnName: "amount", ColumnType: "decimal(10,2)", IsPk: "0"}
	InitColumnField(c5, tbl)
	if c5.JavaType != model.TypeBigDecimal {
		t.Errorf("amount javaType=%q want BigDecimal", c5.JavaType)
	}
}

// TestConvertToCamelCaseAndHelpers 校类名/业务名/模块名/功能名等字符串原语与 Java 逐字一致。
func TestConvertToCamelCaseAndHelpers(t *testing.T) {
	if got := convertToCamelCase("sys_user_online"); got != "SysUserOnline" {
		t.Errorf("convertToCamelCase=%q", got)
	}
	if got := toCamelCase("user_name"); got != "userName" {
		t.Errorf("toCamelCase=%q", got)
	}
	if got := GetBusinessName("sys_user_online"); got != "online" {
		t.Errorf("GetBusinessName=%q", got)
	}
	if got := GetModuleName("com.ruoyi.system"); got != "system" {
		t.Errorf("GetModuleName=%q", got)
	}
	if got := ReplaceText("用户表若依"); got != "用户" {
		t.Errorf("ReplaceText=%q", got)
	}
}
