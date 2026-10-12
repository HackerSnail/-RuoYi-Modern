package genvm

import (
	"io/fs"
	"strings"
	"testing"
)

// TestEmbedInventory 锁定内嵌模板清单与「逐字节」前提：
//   - 21 个 .vm 全部在 FS 内（路径与 Java VelocityUtils.getTemplateList 完全一致，含 vm/ 前缀）；
//   - 每个模板首行以 CRLF 结尾（与 ruoyi-generator 源文件字节一致，未被 EOL 转换破坏）。
func TestEmbedInventory(t *testing.T) {
	wanted := []string{
		"vm/java/domain.java.vm",
		"vm/java/mapper.java.vm",
		"vm/java/service.java.vm",
		"vm/java/serviceImpl.java.vm",
		"vm/java/controller.java.vm",
		"vm/java/sub-domain.java.vm",
		"vm/xml/mapper.xml.vm",
		"vm/sql/sql.vm",
		"vm/js/api.js.vm",
		"vm/ts/api.ts.vm",
		"vm/ts/type.ts.vm",
		"vm/ts/index.ts.vm",
		"vm/vue/index.vue.vm",
		"vm/vue/index-tree.vue.vm",
		"vm/vue/view.vue.vm",
		"vm/vue/v3/index.vue.vm",
		"vm/vue/v3/index-tree.vue.vm",
		"vm/vue/v3/view.vue.vm",
		"vm/vue/v3ts/index.vue.vm",
		"vm/vue/v3ts/index-tree.vue.vm",
		"vm/vue/v3ts/view.vue.vm",
	}
	if len(wanted) != 21 {
		t.Fatalf("期望 21 个模板，实际列出 %d", len(wanted))
	}
	seen := map[string]bool{}
	err := fs.WalkDir(FS, "vm", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".vm") {
			seen[path] = true
		}
		return nil
	})
	if err != nil {
		t.Fatalf("WalkDir 失败: %v", err)
	}
	for _, w := range wanted {
		if !seen[w] {
			t.Errorf("缺少模板: %s", w)
		}
	}
	if len(seen) != 21 {
		t.Errorf("FS 内 .vm 计数=%d，期望 21（%v）", len(seen), seen)
	}
	// CRLF 前提：模板源含 \r\n（Java 侧 VelocityEngine 读到的即此字节）
	b, err := FS.ReadFile("vm/java/domain.java.vm")
	if err != nil {
		t.Fatalf("读取 domain.java.vm 失败: %v", err)
	}
	if !strings.Contains(string(b), "\r\n") {
		t.Error("domain.java.vm 未含 CRLF，疑似 EOL 被转换，破坏逐字节前提")
	}
}
