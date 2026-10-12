package service

// genzip_test.go —— 16-C 落地路径的行为级单测（DB 无关）：
//   - zipTables：index-bak.ts 跨表合并成单一条目、其余条目正常写入；
//   - writeTableToDisk：跳过下载/预览专属模板、仅落 Java/XML 文件、自动建父目录；
//   - GetGenPath：genPath="/" 回退工作目录/src 分支；
//   - DownloadCode：空表名/超上限两条错误文案逐字对齐 Java。
//
// 表用 testdata/gen/fixtures 的 JSON 反序列化后 SetPkColumn（纯函数，无 DB）装配，直接喂给 zip/落盘核心。

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"ruoyi-goframe/internal/model"
)

// fixturesDir 相对 service 包目录（internal/service）上溯三级到仓库根的 testdata/gen/fixtures。
func fixturesDir(t *testing.T) string {
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	return filepath.Join(wd, "..", "..", "..", "testdata", "gen", "fixtures")
}

// loadFixture 反序列化夹具为 GenTable 并 SetPkColumn（复刻生产装配前置，纯函数无 DB）。
func loadFixture(t *testing.T, id string) *model.GenTable {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(fixturesDir(t), id+".json"))
	if err != nil {
		t.Fatalf("读夹具 %s: %v", id, err)
	}
	var table model.GenTable
	if err := json.Unmarshal(raw, &table); err != nil {
		t.Fatalf("夹具反序列化 %s: %v", id, err)
	}
	if err := SetPkColumn(&table); err != nil {
		t.Fatalf("SetPkColumn %s: %v", id, err)
	}
	return &table
}

// zipEntries 解压为「条目名→内容」，条目名重复即报错（用于断言 index-bak.ts 已合并为单一键）。
func zipEntries(t *testing.T, data []byte) map[string]string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("zip 解压失败: %v", err)
	}
	out := map[string]string{}
	for _, f := range zr.File {
		if _, dup := out[f.Name]; dup {
			t.Fatalf("zip 出现重名条目（index-bak.ts 未合并？）: %s", f.Name)
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatalf("开条目 %s: %v", f.Name, err)
		}
		var b bytes.Buffer
		if _, err := b.ReadFrom(rc); err != nil {
			t.Fatalf("读条目 %s: %v", f.Name, err)
		}
		rc.Close()
		out[f.Name] = b.String()
	}
	return out
}

// TestZipTablesIndexBakMerge 验证两张 epts 表打包时，index-bak.ts 合并为单一条目且追加两表 export 行。
// 第二表覆写类名/业务名/模块名，使除固定路径 index-bak.ts（文件名不含业务名）外的条目均不重名，
// 从而 zipEntries 的重名断言只对「index-bak.ts 是否真被合并」敏感。
func TestZipTablesIndexBakMerge(t *testing.T) {
	t1 := loadFixture(t, "crud_epts")
	t2 := loadFixture(t, "crud_epts")
	t2.ClassName = "Zeta"
	t2.BusinessName = "zeta"
	t2.ModuleName = "zeta"
	entries := zipEntries(t, mustZip(t, t1, t2))

	const bak = "vue/types/api/index-bak.ts"
	content, ok := entries[bak]
	if !ok {
		t.Fatalf("缺 index-bak.ts 条目，实得条目: %v", keysOf(entries))
	}
	// 合并语义：仅保留一份基线 + 追加的 "export * from" 行，两表至少各贡献一条 → 至少 2 条 export 行。
	if n := strings.Count(content, "export * from"); n < 2 {
		t.Fatalf("index-bak.ts 合并后 export 行数不足（期望>=2，实得 %d）", n)
	}
	// 两表 domain.java 应因类名不同而各自成条目（未误合并/未漏写）。
	if !hasSuffix(entries, "/domain/Zeta.java") {
		t.Fatalf("缺少第二表 domain 条目 /domain/Zeta.java，全条目=%v", keysOf(entries))
	}
}

// TestWriteTableToDiskSkipsTemplates 验证落盘仅写 Java/XML，跳过 sql/api/类型/列表页等专属模板，且自动建父目录。
func TestWriteTableToDiskSkipsTemplates(t *testing.T) {
	dir := t.TempDir()
	table := loadFixture(t, "crud_eui")
	table.GenPath = dir // 自定义路径分支（非 "/"）

	if err := writeTableToDisk(table); err != nil {
		t.Fatalf("落盘失败: %v", err)
	}

	var sawJava, sawXml bool
	err := filepath.Walk(dir, func(p string, info os.FileInfo, e error) error {
		if e != nil {
			return e
		}
		if info.IsDir() {
			return nil
		}
		switch filepath.Ext(p) {
		case ".java":
			sawJava = true
		case ".xml":
			sawXml = true
		case ".sql", ".vue", ".js", ".ts":
			t.Errorf("落盘不应写下载/预览专属文件: %s", p)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("遍历落盘目录失败: %v", err)
	}
	if !sawJava || !sawXml {
		t.Fatalf("落盘缺少 Java/XML 文件（sawJava=%v sawXml=%v）", sawJava, sawXml)
	}
}

// TestGetGenPathRootFallback 验证 genPath="/" 回退到「工作目录/src/文件名」分支。
func TestGetGenPathRootFallback(t *testing.T) {
	wd, _ := os.Getwd()
	table := loadFixture(t, "crud_eui")
	table.GenPath = "/"
	got, err := GetGenPath("vm/java/domain.java.vm", table)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(got, filepath.Join(wd, "src")) {
		t.Fatalf("genPath=/ 应回退工作目录/src，实得 %s", got)
	}
	if !strings.HasSuffix(got, ".java") {
		t.Fatalf("回退路径应以 .java 结尾，实得 %s", got)
	}
}

// TestDownloadCodeGuardErrors 验证两条前置校验错误文案逐字对齐 Java（均早于任何 DB 访问）。
func TestDownloadCodeGuardErrors(t *testing.T) {
	if _, err := DownloadCode(nil, []string{}); err == nil || err.Error() != "请选择要生成的表" {
		t.Fatalf("空表名文案不符: %v", err)
	}
	many := make([]string, maxBatchGenTable+1)
	for i := range many {
		many[i] = "t"
	}
	if _, err := DownloadCode(nil, many); err == nil ||
		err.Error() != "单次批量生成不得超过 50 张表，请分批生成" {
		t.Fatalf("超上限文案不符: %v", err)
	}
}

// TestGenCodeRejectsPathTraversal 验证 className 派生出 ".." 段时，genCode 落盘被拒（spec assertSafeSegment）。
func TestGenCodeRejectsPathTraversal(t *testing.T) {
	table := loadFixture(t, "crud_eui")
	table.ClassName = "../evil"
	table.GenPath = t.TempDir()
	err := writeTableToDisk(table)
	if err == nil || !strings.Contains(err.Error(), "疑似路径穿越") {
		t.Fatalf("应因路径穿越被拒，实得 err=%v", err)
	}
}

// TestDownloadRejectsPathTraversal 验证 ZIP 条目名同样拒绝穿越段（download/batchGenCode 一并收口）。
func TestDownloadRejectsPathTraversal(t *testing.T) {
	table := loadFixture(t, "crud_eui")
	table.ClassName = "../evil"
	if _, err := zipTables([]*model.GenTable{table}); err == nil ||
		!strings.Contains(err.Error(), "疑似路径穿越") {
		t.Fatalf("download 应因路径穿越被拒，实得 err=%v", err)
	}
}

// TestGetGenPathRejectsGenRootTraversal 验证 genPath 根目录含 .. 逃逸段时被拒（spec §315/§353）。
func TestGetGenPathRejectsGenRootTraversal(t *testing.T) {
	table := loadFixture(t, "crud_eui")
	table.GenPath = "../../../../evil"
	if _, err := GetGenPath("vm/java/domain.java.vm", table); err == nil ||
		!strings.Contains(err.Error(), "疑似路径穿越") {
		t.Fatalf("genPath 穿越应被拒，实得 err=%v", err)
	}
	// 反例：合法绝对/相对根应放行（无 .. 段）。
	table.GenPath = t.TempDir()
	if _, err := GetGenPath("vm/java/domain.java.vm", table); err != nil {
		t.Fatalf("合法 genPath 不应被拒，实得 err=%v", err)
	}
}

// ---- 测试辅助 ----

func mustZip(t *testing.T, tables ...*model.GenTable) []byte {
	t.Helper()
	data, err := zipTables(tables)
	if err != nil {
		t.Fatalf("zipTables 失败: %v", err)
	}
	return data
}

func keysOf(m map[string]string) []string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	return ks
}

func hasSuffix(entries map[string]string, suffix string) bool {
	for k := range entries {
		if strings.HasSuffix(k, suffix) {
			return true
		}
	}
	return false
}
