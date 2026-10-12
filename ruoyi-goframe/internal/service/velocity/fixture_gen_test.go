package velocity

// fixture_gen_test.go —— 生成全笛卡尔夹具矩阵（3 类别 × 3 前端类型 × 2 genView = 18）。
//
// 仅在显式设 GEN_WRITE_FIXTURES=1 时运行（避免污染常规 go test）。产出的 JSON 同时被
// Java oracle（fastjson2 → GenTable）与 Go 比对（encoding/json → model.GenTable）消费，
// 键名取自 model 的 json tag（与 Java 字段名逐字一致）。

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"testing"

	"ruoyi-goframe/internal/model"
)

// writeFixtures 用测试自定义 flag 门控（env 在本机 shell 传递不稳，改用 flag 可靠）。
var writeFixtures = flag.Bool("write-fixtures", false, "生成全笛卡尔夹具矩阵到 testdata/gen/fixtures")

func col(columnName, comment, ctype, jtype, jfield, pk, inc, req, ins, edit, list, query, qt, ht, dict string) *model.GenTableColumn {
	return &model.GenTableColumn{
		ColumnName: columnName, ColumnComment: comment, ColumnType: ctype,
		JavaType: jtype, JavaField: jfield,
		IsPk: pk, IsIncrement: inc, IsRequired: req, IsInsert: ins, IsEdit: edit,
		IsList: list, IsQuery: query, QueryType: qt, HtmlType: ht, DictType: dict,
	}
}

func baseColumns() []*model.GenTableColumn {
	return []*model.GenTableColumn{
		col("id", "主键ID", "bigint(20)", "Long", "id", "1", "1", "0", "0", "0", "1", "0", "EQ", "input", ""),
		col("user_name", "用户名称", "varchar(50)", "String", "userName", "0", "0", "1", "1", "1", "1", "1", "LIKE", "input", ""),
		col("a_count", "计数", "int(11)", "Integer", "aCount", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
		col("status", "状态（0正常 1停用）", "char(1)", "String", "status", "0", "0", "0", "1", "1", "1", "1", "EQ", "radio", "sys_normal_disable"),
		col("price", "价格（元）", "decimal(10,2)", "BigDecimal", "price", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
		col("birthday", "出生日期", "datetime", "Date", "birthday", "0", "0", "0", "1", "1", "1", "0", "EQ", "datetime", ""),
		col("create_by", "创建者", "varchar(64)", "String", "createBy", "0", "0", "0", "0", "0", "0", "0", "EQ", "input", ""),
	}
}

func treeColumns() []*model.GenTableColumn {
	return []*model.GenTableColumn{
		col("dept_id", "部门ID", "bigint(20)", "Long", "deptId", "1", "1", "0", "0", "0", "1", "0", "EQ", "input", ""),
		col("parent_id", "父部门ID", "bigint(20)", "Long", "parentId", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
		col("dept_name", "部门名称", "varchar(30)", "String", "deptName", "0", "0", "1", "1", "1", "1", "1", "LIKE", "input", ""),
		col("order_num", "显示顺序", "int(4)", "Integer", "orderNum", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
		col("status", "部门状态（0正常 1停用）", "char(1)", "String", "status", "0", "0", "0", "1", "1", "1", "1", "EQ", "select", "sys_normal_disable"),
		col("create_time", "创建时间", "datetime", "Date", "createTime", "0", "0", "0", "0", "0", "1", "0", "EQ", "datetime", ""),
	}
}

func subDetailColumns() []*model.GenTableColumn {
	return []*model.GenTableColumn{
		col("detail_id", "明细ID", "bigint(20)", "Long", "detailId", "1", "1", "0", "0", "0", "1", "0", "EQ", "input", ""),
		col("order_id", "订单ID", "bigint(20)", "Long", "orderId", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
		col("detail_name", "明细名称", "varchar(50)", "String", "detailName", "0", "0", "1", "1", "1", "1", "1", "LIKE", "input", ""),
		col("item_price", "单项价格（元）", "decimal(10,2)", "BigDecimal", "itemPrice", "0", "0", "0", "1", "1", "1", "0", "EQ", "input", ""),
	}
}

func optionsJSON(category string, view bool) string {
	m := map[string]interface{}{"parentMenuId": "3"}
	if view {
		m["genView"] = true
	}
	if category == "tree" {
		m["treeCode"] = "dept_id"
		m["treeParentCode"] = "parent_id"
		m["treeName"] = "dept_name"
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func buildTable(category, webType string, view bool) *model.GenTable {
	t := &model.GenTable{
		TableName: "sys_gen_demo", TableComment: "生成演示表", ClassName: "GenDemo",
		PackageName: "com.ruoyi.system", ModuleName: "system", BusinessName: "demo",
		FunctionName: "生成演示", FunctionAuthor: "ruoyi", GenType: "0", GenPath: "/workspace",
		TplCategory: category, TplWebType: webType,
	}
	fcn := 1
	t.FormColNum = &fcn
	t.Options = optionsJSON(category, view)
	switch category {
	case "tree":
		t.Columns = treeColumns()
	case "sub":
		t.Columns = []*model.GenTableColumn{
			col("order_id", "订单ID", "bigint(20)", "Long", "orderId", "1", "1", "0", "0", "0", "1", "0", "EQ", "input", ""),
			col("order_name", "订单名称", "varchar(50)", "String", "orderName", "0", "0", "1", "1", "1", "1", "1", "LIKE", "input", ""),
			col("remark", "备注", "varchar(500)", "String", "remark", "0", "0", "0", "1", "1", "1", "0", "EQ", "textarea", ""),
			col("create_time", "创建时间", "datetime", "Date", "createTime", "0", "0", "0", "0", "0", "1", "0", "EQ", "datetime", ""),
		}
		t.SubTableName = "sys_gen_detail"
		t.SubTableFkName = "order_id"
		t.SubTable = &model.GenTable{
			TableName: "sys_gen_detail", TableComment: "生成明细表", ClassName: "GenDemoDetail",
			PackageName: "com.ruoyi.system", ModuleName: "system", BusinessName: "demo",
			FunctionName: "生成明细", FunctionAuthor: "ruoyi", GenType: "0", GenPath: "/workspace",
			TplCategory: "crud", Columns: subDetailColumns(),
		}
	default: // crud
		t.Columns = baseColumns()
	}
	return t
}

func TestWriteFixtures(t *testing.T) {
	if !*writeFixtures {
		t.Skip("设 -write-fixtures 才生成夹具")
	}
	root := repoRoot(t)
	dir := filepath.Join(root, "testdata", "gen", "fixtures")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	webSlug := map[string]string{"element-ui": "eui", "element-plus": "epi", "element-plus-typescript": "epts"}
	cats := []string{"crud", "tree", "sub"}
	webs := []string{"element-ui", "element-plus", "element-plus-typescript"}
	views := []bool{false, true}
	n := 0
	for _, cat := range cats {
		for _, web := range webs {
			for _, view := range views {
				name := cat + "_" + webSlug[web]
				if view {
					name += "_view"
				}
				name += ".json"
				tbl := buildTable(cat, web, view)
				b, err := json.MarshalIndent(tbl, "", "  ")
				if err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(dir, name), b, 0o644); err != nil {
					t.Fatal(err)
				}
				n++
			}
		}
	}
	t.Logf("写出 %d 个夹具到 %s", n, dir)
}
