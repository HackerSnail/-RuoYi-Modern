// Package service —— 代码生成落地，逐字对齐 GenTableServiceImpl 的 downloadCode/generatorCode(String[])/
// generatorCode(String) 私有编排 + getGenPath。16-C：渲染引擎（velocity）已就位，本文件只做「把渲染结果
// 打成 ZIP 供下载」与「按自定义路径落盘（genCode）」两条落地路径，行为契约对齐 Java。
//
// 与 Java 的分层差异（如实标注）：
//   - Java 用 java.util.zip.ZipOutputStream(默认 DEFLATED)，本文件用 archive/zip（默认亦 Deflate）；
//     ZIP 不做逐字节裁判（golden 只裁模板渲染），只保证条目名/内容/合并语义与 Java 一致、可解压。
//   - Java FileUtils.writeStringToFile 会自动建父目录，Go os.WriteFile 不会 → 落盘前显式 MkdirAll。
//   - 模板渲染保留 CRLF（见 velocity.RenderTemplate），ZIP 条目与落盘字节均与 Java 侧同源一致。
package service

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service/velocity"
)

// maxBatchGenTable 对齐 MAX_BATCH_GEN_TABLES：单次批量生成的表数上限，防全量驻留内存 + zip 触发 OOM/超时。
const maxBatchGenTable = 50

// genCodeSkipList 对齐 generatorCode(String) 的 containsAny 跳过项：这些模板只适合下载/预览，不写本地工程目录。
var genCodeSkipList = []string{
	"sql.vm", "api.js.vm", "api.ts.vm", "type.ts.vm", "index.ts.vm",
	"index.vue.vm", "index-tree.vue.vm", "view.vue.vm",
}

// safeSegRe 复刻 spec 的 assertSafeSegment 段白名单。注：spec 字面写 ^[A-Za-z0-9_.]+$，但它
// 自己的产物 index-bak.ts 含连字符会被误拒，故实作放宽纳入 '-'（仍无 '/'、''、控制字符，足以拦穿越）。
var safeSegRe = regexp.MustCompile(`^[A-Za-z0-9_.\-]+$`)

// assertSafeGenPath 落地批次-16 spec 的路径穿越防御（Java 无此加固，Go 侧行为不低于 Java）：
// 把 velocity.GetFileName 产出的正斜杠相对路径按 '/' 逐段校验 ^[A-Za-z0-9_.]+$，并显式拒 '.'/'..'
//（二者虽命中白名单却是穿越）。任一段非法即抛「代码生成非法<段>，疑似路径穿越，已拒绝：<段>」。
// 消费点两处：genCode 落盘（GetGenPath）与 download/batchGenCode 的 ZIP 条目名（renderTableToZip），
// 二者均源自可编辑的 gen_table（className/packageName/moduleName/businessName/genPath），故须收口。
func assertSafeGenPath(fileName string) error {
	for _, seg := range strings.Split(fileName, "/") {
		if seg == "." || seg == ".." || !safeSegRe.MatchString(seg) {
			return fmt.Errorf("代码生成非法%s，疑似路径穿越，已拒绝：%s", seg, seg)
		}
	}
	return nil
}

// assertSafeGenRoot 校验落盘根目录 genPath 不含路径穿越段（..）。genPath 来自可编辑的 gen_table，
// spec §315/§353 要求拒绝越出意图根以外的写（如 ../../../../evil）：以「任一目录段等于 ..」为穿越
// 判据（绝对路径 D:/gen、相对路径 src/gen 均合法放行，仅拦 ../.. 逃逸；'.' 为当前目录、无害不拦）。
func assertSafeGenRoot(genPath string) error {
	for _, seg := range strings.FieldsFunc(genPath, func(r rune) bool { return r == '/' || r == '\\' }) {
		if seg == ".." {
			return fmt.Errorf("代码生成非法genPath，疑似路径穿越，已拒绝：%s", genPath)
		}
	}
	return nil
}

// GetGenPath 对齐 static getGenPath：genPath 为 "/"（默认占位）→ 工作目录/src/文件名；否则 genPath/文件名。
// 文件名取自 velocity.GetFileName（正斜杠路径），经 filepath.FromSlash 转本机分隔后 Join。
// 落盘前对两段分别收口：文件名段走 assertSafeGenPath，根目录段走 assertSafeGenRoot（spec §315）。
func GetGenPath(template string, t *model.GenTable) (string, error) {
	fileName := velocity.GetFileName(template, t)
	if err := assertSafeGenPath(fileName); err != nil {
		return "", err
	}
	if t.GenPath == "/" {
		wd, err := os.Getwd()
		if err != nil {
			return "", err
		}
		return filepath.Join(wd, "src", filepath.FromSlash(fileName)), nil
	}
	if err := assertSafeGenRoot(t.GenPath); err != nil {
		return "", err
	}
	return filepath.Join(t.GenPath, filepath.FromSlash(fileName)), nil
}

// DownloadCode 对齐 downloadCode(String[])：空/超上限即抛业务异常；逐表查库装配后交 zipTables 打包。
// 返回 zip 字节，由 controller 写响应流。
func DownloadCode(ctx context.Context, tableNames []string) ([]byte, error) {
	if len(tableNames) == 0 {
		return nil, fmt.Errorf("请选择要生成的表")
	}
	if len(tableNames) > maxBatchGenTable {
		return nil, fmt.Errorf("单次批量生成不得超过 %d 张表，请分批生成", maxBatchGenTable)
	}
	tables := make([]*model.GenTable, 0, len(tableNames))
	for _, tableName := range tableNames {
		table, err := loadAssembleTable(ctx, tableName)
		if err != nil {
			return nil, err
		}
		tables = append(tables, table)
	}
	return zipTables(tables)
}

// loadAssembleTable 对齐 private generatorCode 的 DB 前置段：查表→装配子表→定主键。表不存在即上抛。
func loadAssembleTable(ctx context.Context, tableName string) (*model.GenTable, error) {
	table, err := RequireTableByName(ctx, tableName)
	if err != nil {
		return nil, err
	}
	if err := SetSubTable(ctx, table); err != nil {
		return nil, err
	}
	if err := SetPkColumn(table); err != nil {
		return nil, err
	}
	return table, nil
}

// zipTables 纯打包核心（无 DB）：逐表渲染写 ZIP，index-bak.ts 跨表合并；写失败累积、末尾统一抛出
//（不静默返回残缺 zip）。抽出以便用 fixture 直接构造表做行为级单测。
func zipTables(tables []*model.GenTable) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	// typeFiles 缓存需跨表合并的 index-bak.ts；order 保插入序以让 Go 端输出确定（Java HashMap 序不确定但
	// 现实仅一个键，不影响功能）。
	typeFiles := map[string]*strings.Builder{}
	var typeFilesOrder []string
	var zipFailures []string
	for _, table := range tables {
		if err := renderTableToZip(table, zw, typeFiles, &typeFilesOrder, &zipFailures); err != nil {
			return nil, err
		}
	}
	// 合并完再把聚合结果作为条目写入 zip（对齐 Java downloadCode 收尾循环）。
	for _, name := range typeFilesOrder {
		if err := writeToZip(zw, name, typeFiles[name].String()); err != nil {
			zipFailures = append(zipFailures, name)
		}
	}
	// Close 确保 zip 中央目录写入完成（不写则不可解压）。
	if err := zw.Close(); err != nil {
		return nil, err
	}
	if len(zipFailures) > 0 {
		return nil, fmt.Errorf("部分文件写入ZIP失败：%s", javaListStr(zipFailures))
	}
	return buf.Bytes(), nil
}

// renderTableToZip 对齐 private generatorCode 的渲染循环（无 DB）：prepareContext→逐模板渲染→getFileName；
// index-bak.ts 收集合并、其余直接写条目。
func renderTableToZip(table *model.GenTable, zw *zip.Writer,
	typeFiles map[string]*strings.Builder, typeFilesOrder *[]string, zipFailures *[]string) error {
	ctxMap := velocity.PrepareContext(table)
	for _, template := range velocity.GetTemplateList(table) {
		content, err := velocity.RenderTemplate(template, ctxMap)
		if err != nil {
			return err
		}
		fileName := velocity.GetFileName(template, table)
		// 路径穿越防御：ZIP 条目名同样逐段校验，非法即中止整批下载（对齐 spec assertSafeSegment）。
		if err := assertSafeGenPath(fileName); err != nil {
			return err
		}
		// index-bak.ts：多表共享同一声明文件，首表整份基线、后续表仅追加 export * from 行。
		if strings.Contains(fileName, "index-bak.ts") {
			if _, ok := typeFiles[fileName]; !ok {
				builder := &strings.Builder{}
				builder.WriteString(content)
				typeFiles[fileName] = builder
				*typeFilesOrder = append(*typeFilesOrder, fileName)
			} else {
				// 后续表：仅提取 export * from 行追加，避免重复模板样板内容。
				for _, line := range strings.Split(content, "\n") {
					if strings.HasPrefix(line, "export * from") {
						typeFiles[fileName].WriteByte('\n')
						typeFiles[fileName].WriteString(line)
					}
				}
			}
		} else if err := writeToZip(zw, fileName, content); err != nil {
			*zipFailures = append(*zipFailures, fileName)
		}
	}
	return nil
}

// writeToZip 把字符串内容作为一个 ZIP 条目写出；返回 error 供调用方累积失败名单（对齐 Java 不吞异常）。
func writeToZip(zw *zip.Writer, fileName, content string) error {
	w, err := zw.Create(fileName)
	if err != nil {
		return err
	}
	if _, err := io.WriteString(w, content); err != nil {
		return err
	}
	return nil
}

// GeneratorCodeToDisk 对齐 generatorCode(String)（自定义路径落盘）：查表装配后交 writeTableToDisk。
func GeneratorCodeToDisk(ctx context.Context, tableName string) error {
	table, err := loadAssembleTable(ctx, tableName)
	if err != nil {
		return err
	}
	return writeTableToDisk(table)
}

// writeTableToDisk 纯落盘核心（无 DB）：prepareContext→逐模板渲染，跳过下载/预览专属模板，
// 其余按 getGenPath 落盘（UTF-8、自动建父目录）。写盘失败包装「渲染模板失败」。抽出以便行为级单测。
func writeTableToDisk(table *model.GenTable) error {
	ctxMap := velocity.PrepareContext(table)
	for _, template := range velocity.GetTemplateList(table) {
		// 跳过 SQL/前端 API/类型/列表页等模板（仅适合下载/预览，不写本地工程目录）。
		if containsAny(template, genCodeSkipList...) {
			continue
		}
		content, err := velocity.RenderTemplate(template, ctxMap)
		if err != nil {
			return err
		}
		path, err := GetGenPath(template, table)
		if err != nil {
			// 取径/穿越错误原样上抛（ServiceException 语义），区别于写盘 IO 失败。
			return err
		}
		if err := writeFileUTF8(path, content); err != nil {
			return fmt.Errorf("渲染模板失败，表名：%s", table.TableName)
		}
	}
	return nil
}

// ---- 辅助 ----

// containsAny 对齐 StringUtils.containsAny(CharSequence, CharSequence...)：任一子串命中即 true。
func containsAny(s string, subs ...string) bool {
	for _, sub := range subs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

// writeFileUTF8 对齐 FileUtils.writeStringToFile(file, content, UTF_8)：建父目录后覆盖写 UTF-8 字节。
func writeFileUTF8(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

// javaListStr 复刻 ArrayList.toString()：形如 "[a, b]"（逗号+空格），供 ZIP 失败文案对齐 Java。
func javaListStr(items []string) string {
	return "[" + strings.Join(items, ", ") + "]"
}
