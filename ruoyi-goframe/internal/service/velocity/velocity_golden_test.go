package velocity

// velocity_golden_test.go —— 批次 16-B② 的逐字节金标准比对。
//
// 以 Java oracle（GenGoldenOracleTest）产出的 golden 为唯一裁判：对每个 fixture，用 Go 侧
// PrepareContext + 解释器渲染 GetTemplateList 的每个模板，与 testdata/gen/golden/<fixture>/<key>
// 做 bytes.Equal。任一不符即报首个差异行，驱动解释器/上下文收敛。

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service/genvm"
)

const fixedDate = "2026-01-01" // 与 oracle FIXED_DATE 一致

func repoRoot(t *testing.T) string {
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	p := wd
	for {
		// 以 testdata/gen/fixtures（始终位于仓库根、三工程并列重构后仍如此）为锚，
		// 与模块层级布局解耦：勿再假设根目录直接含 ruoyi-generator（已下沉到 ruoyi-springboot/）。
		if isDir(filepath.Join(p, "testdata", "gen", "fixtures")) {
			return p
		}
		parent := filepath.Dir(p)
		if parent == p {
			t.Fatalf("无法定位仓库根（自 %s 向上，未找到 testdata/gen/fixtures）", wd)
		}
		p = parent
	}
}

func isDir(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func TestGoldenByteEqual(t *testing.T) {
	root := repoRoot(t)
	fixturesDir := filepath.Join(root, "testdata", "gen", "fixtures")
	goldenDir := filepath.Join(root, "testdata", "gen", "golden")

	entries, err := os.ReadDir(fixturesDir)
	if err != nil {
		t.Fatalf("读夹具目录失败 %s: %v", fixturesDir, err)
	}
	checked := 0
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		fixtureID := strings.TrimSuffix(e.Name(), ".json")
		t.Run(fixtureID, func(t *testing.T) {
			raw, err := os.ReadFile(filepath.Join(fixturesDir, e.Name()))
			if err != nil {
				t.Fatal(err)
			}
			var table model.GenTable
			if err := json.Unmarshal(raw, &table); err != nil {
				t.Fatalf("夹具反序列化失败: %v", err)
			}
			replicateSetPkColumn(&table)
			ctx := PrepareContext(&table)
			ctx["datetime"] = fixedDate

			for _, tpl := range GetTemplateList(&table) {
				got, err := renderTemplate(tpl, ctx)
				if err != nil {
					t.Errorf("[%s] 渲染失败 %s: %v", fixtureID, tpl, err)
					continue
				}
				key := strings.ReplaceAll(tpl, "/", "_")
				key = strings.TrimSuffix(key, ".vm") + ".golden"
				gold, err := os.ReadFile(filepath.Join(goldenDir, fixtureID, key))
				if err != nil {
					t.Errorf("[%s] 读 golden 失败 %s: %v", fixtureID, key, err)
					continue
				}
				checked++
				if !bytes.Equal([]byte(got), gold) {
					t.Errorf("[%s] %s 逐字节不符:\n%s", fixtureID, tpl, firstDiff(got, string(gold)))
				}
			}
		})
	}
	t.Logf("共比对 %d 个模板 golden", checked)
}

// renderTemplate 从 embed FS 读取 .vm 源，解析并渲染。
func renderTemplate(tplPath string, ctx map[string]interface{}) (string, error) {
	b, err := genvm.FS.ReadFile(tplPath)
	if err != nil {
		return "", fmt.Errorf("读模板 %s: %w", tplPath, err)
	}
	// 不归一化行尾：Velocity 保留模板原始 CRLF，golden 亦为 CRLF，逐字节须一致。
	src := string(b)
	elems, err := ParseTemplate(src)
	if err != nil {
		return "", fmt.Errorf("解析模板 %s: %w", tplPath, err)
	}
	return Render(elems, ctx)
}

// replicateSetPkColumn 复刻 GenTableServiceImpl.setPkColumn：首个 isPk 列，否则首列；子表同理。
func replicateSetPkColumn(t *model.GenTable) {
	t.PkColumn = pickPk(t.Columns)
	if t.TplCategory == model.TplSub && t.SubTable != nil {
		t.SubTable.PkColumn = pickPk(t.SubTable.Columns)
	}
}

func pickPk(cols []*model.GenTableColumn) *model.GenTableColumn {
	if len(cols) == 0 {
		return nil
	}
	for _, c := range cols {
		if c.IsPkFlag() {
			return c
		}
	}
	return cols[0]
}

// firstDiff 返回首个差异行（got vs want）的可视化，便于定位。
func firstDiff(got, want string) string {
	g := strings.Split(got, "\n")
	w := strings.Split(want, "\n")
	n := len(g)
	if len(w) < n {
		n = len(w)
	}
	for i := 0; i < n; i++ {
		if g[i] != w[i] {
			return fmt.Sprintf("首个差异在第 %d 行\n  got : %q\n  want: %q", i+1, g[i], w[i])
		}
	}
	if len(g) != len(w) {
		return fmt.Sprintf("行数不同 got=%d want=%d（尾部差异）\n  got 尾: %q\n  want尾: %q", len(g), len(w), tail(g), tail(w))
	}
	return "内容含行尾/空白差异（逐字节不等但按行相等）"
}

func tail(lines []string) string {
	if len(lines) == 0 {
		return ""
	}
	j := len(lines) - 1
	if j > 5 {
		j = 5
	}
	return strings.Join(lines[len(lines)-j:], "\\n")
}
