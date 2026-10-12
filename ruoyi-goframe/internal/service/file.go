// Package service —— 通用文件基建：复刻 Java FileUploadUtils/FileUtils/MimeTypeUtils/FileTypeUtils
// 的上传落盘、后缀白名单 + 内容魔数核验、基目录锚定防穿越、下载可用性校验、日期目录 + Seq 命名，
// 以及 RuoYiConfig 的 profile/upload/download 路径推导。是 /common 上传下载、头像上传(P1 批次2)、
// Excel 导出下载(P3) 的公共底座。
//
// 契约对齐要点（务必与 Java 一致）：
//  1. 落盘根取 ruoyi.profile（config.yaml，默认 D:/ruoyi/uploadPath），虚拟访问前缀 /profile。
//  2. 文件名 = 日期目录(yyyy/MM/dd)/原基名_序列(yyyyMMddHHmmss + 机器码A + 3位循环计数).后缀。
//  3. 校验顺序照搬 Java：文件名长度(>100) → 大小(>50MB) → 后缀白名单 → 内容魔数；文案逐字复用 i18n。
//  4. getAbsoluteFile/isPathUnderBase 做 canonical(Abs+Clean) 基目录锚定，越界拒绝（本机无符号链，未解软链）。
package service

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
)

// resourcePrefix 对应 Constants.RESOURCE_PREFIX，前端访问上传资源的统一虚拟前缀。
const resourcePrefix = "/profile"

// 上传限制，对应 FileUploadUtils.DEFAULT_MAX_SIZE / DEFAULT_FILE_NAME_LENGTH / MAGIC_HEADER_LENGTH。
const (
	defaultMaxSize         = 50 * 1024 * 1024 // 50MB
	defaultFileNameLength  = 100
	defaultMaxSizeMB       = 50
	magicHeaderLength      = 12
	resourceMachineCode    = "A" // Seq.machineCode
)

// 后缀白名单集合，对应 MimeTypeUtils 各分组常量。
var (
	imageExt   = []string{"bmp", "gif", "jpg", "jpeg", "png"}
	officeExt  = []string{"doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "pdf"}
	archiveExt = []string{"rar", "zip", "gz", "bz2"}
	videoExt   = []string{"mp4", "avi", "rmvb"}
	// DefaultAllowedExtension = 图片+Office+压缩+视频 并集（对齐 MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION 顺序）。
	DefaultAllowedExtension = concatExt(imageExt, officeExt, archiveExt, videoExt)
)

func concatExt(groups ...[]string) []string {
	var out []string
	for _, grp := range groups {
		out = append(out, grp...)
	}
	return out
}

// ---- 路径推导（对应 RuoYiConfig.getProfile/getUploadPath/getDownloadPath 等）----

// GetProfile 返回本地上传根目录（ruoyi.profile）。
func GetProfile(ctx context.Context) string {
	return g.Cfg().MustGet(ctx, "ruoyi.profile", "D:/ruoyi/uploadPath").String()
}

// GetUploadPath 对应 getUploadPath()：profile + "/upload"。
func GetUploadPath(ctx context.Context) string { return GetProfile(ctx) + "/upload" }

// GetDownloadPath 对应 getDownloadPath()：profile + "/download/"（注意结尾斜杠）。
func GetDownloadPath(ctx context.Context) string { return GetProfile(ctx) + "/download/" }

// GetAvatarPath 对应 getAvatarPath()：profile + "/avatar"（供批次2 头像复用）。
func GetAvatarPath(ctx context.Context) string { return GetProfile(ctx) + "/avatar" }

// ---- Seq 序列号（对应 Seq.getId(uploadSeqType)）----

var (
	seqMu   sync.Mutex
	seqVal  = 1
	seqInit sync.Once
)

func nextSeq() int {
	seqMu.Lock()
	defer seqMu.Unlock()
	seqInit.Do(func() { seqVal = 1 })
	v := seqVal
	seqVal++
	if seqVal >= 1000 { // 10^3，达到即回卷为 1（对齐 Java getSeq 的循环递增）
		seqVal = 1
	}
	return v
}

// seqId = yyyyMMddHHmmss + "A" + 3位零补齐计数。
func seqId() string {
	return gtime.Now().Format("YmdHis") + resourceMachineCode + padLeft(nextSeq(), 3)
}

func padLeft(n, width int) string {
	s := strconv.Itoa(n)
	for len(s) < width {
		s = "0" + s
	}
	return s
}

// ---- 文件名/后缀解析（对齐 FilenameUtils.getBaseName / getExtension）----

func afterLastSeparator(s string) string {
	i := strings.LastIndexAny(s, `/\`)
	if i < 0 {
		return s
	}
	return s[i+1:]
}

// getExtensionOf 取最后一个 '.' 之后的后缀；无 '.' 或以 '.' 结尾返回空串。
func getExtensionOf(name string) string {
	n := afterLastSeparator(name)
	i := strings.LastIndex(n, ".")
	if i < 0 || i == len(n)-1 {
		return ""
	}
	return n[i+1:]
}

// getBaseNameOf 取路径末段并去掉最后一个 '.' 之后的扩展；无 '.' 或首字符即 '.' 返回末段本身。
func getBaseNameOf(name string) string {
	n := afterLastSeparator(name)
	i := strings.LastIndex(n, ".")
	if i <= 0 {
		return n
	}
	return n[:i]
}

// mimeTypeToExt 对应 MimeTypeUtils.getExtension：去空白、剥参数、转小写后按已知 MIME 映射，未知返回空串。
func mimeTypeToExt(mimeType string) string {
	t := strings.TrimSpace(mimeType)
	if i := strings.Index(t, ";"); i >= 0 {
		t = strings.TrimSpace(t[:i])
	}
	switch strings.ToLower(t) {
	case "image/png":
		return "png"
	case "image/jpeg":
		return "jpeg"
	case "image/jpg": // 非标准，历史保留
		return "jpg"
	case "image/bmp":
		return "bmp"
	case "image/gif":
		return "gif"
	default:
		return ""
	}
}

// ---- 内容魔数核验（对应 FileUploadUtils.matchesMagic，fail-closed）----

func matchesMagic(ext string, header []byte) bool {
	switch ext {
	case "jpg", "jpeg":
		return startsWith(header, 0, 0xFF, 0xD8, 0xFF)
	case "png":
		return startsWith(header, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
	case "gif":
		return startsWith(header, 0, 0x47, 0x49, 0x46, 0x38)
	case "bmp":
		return startsWith(header, 0, 0x42, 0x4D)
	case "pdf":
		return startsWith(header, 0, 0x25, 0x50, 0x44, 0x46)
	case "zip":
		return startsWith(header, 0, 0x50, 0x4B)
	case "docx", "xlsx", "pptx":
		return startsWith(header, 0, 0x50, 0x4B, 0x03, 0x04)
	case "doc", "xls", "ppt":
		return startsWith(header, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
	case "rar":
		return startsWith(header, 0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
	case "gz":
		return startsWith(header, 0, 0x1F, 0x8B)
	case "bz2":
		return startsWith(header, 0, 0x42, 0x5A)
	case "mp4":
		return startsWith(header, 4, 0x66, 0x74, 0x79, 0x70)
	case "avi":
		return startsWith(header, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(header, 8, 0x41, 0x56, 0x49, 0x20)
	default:
		// 无稳定公开签名或签名易变的格式不强校验，放行交由后缀白名单
		return true
	}
}

func startsWith(header []byte, offset int, magic ...int) bool {
	if header == nil || len(header) < offset+len(magic) {
		return false
	}
	for i := range magic {
		if int(header[offset+i])&0xFF != magic[i]&0xFF {
			return false
		}
	}
	return true
}

func isAllowedExtension(ext string, allowed []string) bool {
	for _, a := range allowed {
		if strings.EqualFold(a, ext) {
			return true
		}
	}
	return false
}

// ---- 校验（对应 FileUploadUtils.upload 的入口校验 + assertAllowed）----

// invalidExtMsg 对应 InvalidExtensionException 文案：Arrays.toString(allowed) 形如 "[a, b, c]"。
func invalidExtMsg(allowed []string, ext, filename string) string {
	return fmt.Sprintf("文件[%s]后缀[%s]不正确，请上传%s格式", filename, ext, arraysToString(allowed))
}

func arraysToString(arr []string) string {
	return "[" + strings.Join(arr, ", ") + "]"
}

// assertAllowed 复刻 Java：大小 → 后缀白名单 → 内容魔数。文件名长度检查在 Upload 入口先行（对齐 upload() 顺序）。
func assertAllowed(filename string, size int64, ext string, allowed []string, header []byte) error {
	if size > defaultMaxSize {
		return fmt.Errorf("上传的文件大小超出限制的文件大小！<br/>允许的文件最大大小是：%dMB！", defaultMaxSizeMB)
	}
	if allowed != nil && !isAllowedExtension(ext, allowed) {
		return errors.New(invalidExtMsg(allowed, ext, filename))
	}
	if allowed != nil && !matchesMagic(strings.ToLower(ext), header) {
		return errors.New(invalidExtMsg(allowed, ext, filename))
	}
	return nil
}

// readFileHeader 读取上传文件头前 magicHeaderLength 字节；读失败返回空切片（由魔数核验 fail-closed）。
func readFileHeader(fh *multipart.FileHeader) []byte {
	f, err := fh.Open()
	if err != nil {
		return []byte{}
	}
	defer f.Close()
	buf := make([]byte, magicHeaderLength)
	n, _ := io.ReadFull(f, buf)
	return buf[:n]
}

// ---- 落盘路径 ----

// extractFilename 对应 FileUploadUtils.extractFilename：日期目录/基名_序列.后缀。
func extractFilename(filename, ext string) string {
	return fmt.Sprintf("%s/%s_%s.%s", gtime.Now().Format("Y/m/d"), getBaseNameOf(filename), seqId(), ext)
}

// getAbsoluteFile 归一后核验目标仍在 baseDir 内，越界拒绝写入；并确保父目录存在。
func getAbsoluteFile(baseDir, fileName string) (string, error) {
	dest := filepath.Join(baseDir, filepath.FromSlash(fileName))
	absBase, err := filepath.Abs(baseDir)
	if err != nil {
		return "", err
	}
	absDest, err := filepath.Abs(dest)
	if err != nil {
		return "", err
	}
	sep := string(filepath.Separator)
	if absDest != absBase && !strings.HasPrefix(absDest, absBase+sep) {
		return "", fmt.Errorf("非法的文件路径，拒绝写入上传目录之外：%s", fileName)
	}
	if err := os.MkdirAll(filepath.Dir(absDest), 0o755); err != nil {
		return "", err
	}
	return absDest, nil
}

// getPathFileName 把本地目录相对 profile 的路径换算成前端虚拟访问路径 /profile/[子目录/]文件名。
func getPathFileName(ctx context.Context, baseDir, fileName string) string {
	profile := normSlash(strings.TrimRight(GetProfile(ctx), `/\`))
	up := normSlash(strings.TrimRight(baseDir, `/\`))
	rel := ""
	switch {
	case up == profile:
		rel = ""
	case strings.HasPrefix(up, profile+"/"):
		rel = up[len(profile)+1:]
	}
	p := resourcePrefix
	if rel != "" {
		p += "/" + rel
	}
	return p + "/" + fileName
}

func normSlash(s string) string { return strings.ReplaceAll(s, `\`, "/") }

// Upload 保存单个上传文件到 baseDir，做入口校验 + 魔数核验 + 落盘，返回 (虚拟访问路径, 原始文件名, error)。
// allowed 传 nil 表示不做后缀/魔数校验（本仓 /common 恒传 defaultAllowedExtension）。
func Upload(ctx context.Context, baseDir string, fh *multipart.FileHeader, allowed []string) (string, string, error) {
	if fh == nil {
		return "", "", errors.New("上传文件不能为空")
	}
	filename := fh.Filename
	// 文件名长度检查先行（对齐 Java upload()：在 assertAllowed 之前）
	if utf8.RuneCountInString(filename) > defaultFileNameLength {
		return "", "", fmt.Errorf("上传的文件名最长%d个字符", defaultFileNameLength)
	}
	header := readFileHeader(fh)
	ext := getExtensionOf(filename)
	if ext == "" {
		ext = mimeTypeToExt(fh.Header.Get("Content-Type"))
	}
	if err := assertAllowed(filename, fh.Size, ext, allowed, header); err != nil {
		return "", "", err
	}
	fileName := extractFilename(filename, ext)
	absPath, err := getAbsoluteFile(baseDir, fileName)
	if err != nil {
		return "", "", err
	}
	src, err := fh.Open()
	if err != nil {
		return "", "", err
	}
	defer src.Close()
	dst, err := os.Create(absPath)
	if err != nil {
		return "", "", err
	}
	defer dst.Close()
	if _, err := io.Copy(dst, src); err != nil {
		return "", "", err
	}
	return getPathFileName(ctx, baseDir, fileName), filename, nil
}

// ---- 下载可用性 ----

// getFileTypeOf 对应 FileTypeUtils.getFileType(String)：末段最后一个 '.' 之后的后缀（小写）；无 '.' 返回空串。
func getFileTypeOf(fileName string) string {
	i := strings.LastIndex(fileName, ".")
	if i < 0 {
		return ""
	}
	return strings.ToLower(fileName[i+1:])
}

// containsParentTraversal 按 / 或 \ 逐段拆分（保留空段），任一段等于 ".." 即判定父目录跳转。
// 用保留空段的 Split 而非 FieldsFunc："a..b.pdf"/"a/.../b" 等非跳转段不得误伤。
var sepRe = regexp.MustCompile(`[/\\]`)

func containsParentTraversal(path string) bool {
	for _, seg := range sepRe.Split(path, -1) {
		if seg == ".." {
			return true
		}
	}
	return false
}

// CheckAllowDownload 复刻 FileUtils.checkAllowDownload：非空 + 无 ".." 段 + 后缀落在下载白名单。
func CheckAllowDownload(resource string) bool {
	if resource == "" || containsParentTraversal(resource) {
		return false
	}
	return isAllowedExtension(getFileTypeOf(resource), DefaultAllowedExtension)
}

// IsPathUnderBase 校验 target 归一后是否仍落在 baseDir 之内（下载拼接最终路径的基目录锚定）。
func IsPathUnderBase(baseDir, target string) (bool, error) {
	cb, err := filepath.Abs(baseDir)
	if err != nil {
		return false, err
	}
	ct, err := filepath.Abs(target)
	if err != nil {
		return false, err
	}
	sep := string(filepath.Separator)
	return ct == cb || strings.HasPrefix(ct, cb+sep), nil
}

// StripPrefix 对应 FileUtils.stripPrefix：去掉路径中首个 /profile 之前（含）的前缀片段；不含前缀返回空串。
func StripPrefix(filePath string) string {
	i := strings.Index(filePath, resourcePrefix)
	if i < 0 {
		return ""
	}
	return filePath[i+len(resourcePrefix):]
}

// GetName 对应 FileUtils.getName：取最后一个 / 或 \ 之后的文件名段。
func GetName(fileName string) string {
	if fileName == "" {
		return ""
	}
	i := strings.LastIndexAny(fileName, `/\`)
	if i < 0 {
		return fileName
	}
	return fileName[i+1:]
}

// WriteFileTo 把本地文件字节流写入输出流；文件不存在返回错误（对齐 FileUtils.writeBytes 的 FileNotFoundException）。
func WriteFileTo(w io.Writer, absPath string) error {
	f, err := os.Open(absPath)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(w, f)
	return err
}

// DeleteFileIfExists 对应 FileUtils.deleteFile：仅当为普通文件且存在才删，返回是否删除成功。
func DeleteFileIfExists(absPath string) bool {
	fi, err := os.Stat(absPath)
	if err != nil || fi.IsDir() {
		return false
	}
	return os.Remove(absPath) == nil
}

// ---- 下载响应头（对应 FileUtils.setAttachmentResponseHeader 的编码部分）----

var asciiFallbackRe = regexp.MustCompile(`[^a-zA-Z0-9._-]`)

// PercentEncode 对应 FileUtils.percentEncode：URLEncoder(UTF-8) 后把 '+' 还原为 '%20'。
func PercentEncode(s string) string {
	return strings.ReplaceAll(url.QueryEscape(s), "+", "%20")
}

// AsciiFilenameFallback 对应 toAsciiFilenameFallback：非 [a-zA-Z0-9._-] 字符替换为下划线，空名回 "download"。
func AsciiFilenameFallback(fileName string) string {
	if fileName == "" {
		return "download"
	}
	return asciiFallbackRe.ReplaceAllString(fileName, "_")
}

// BuildContentDisposition 组装 RFC6266 双轨 attachment 头值，并回 percent 编码名（供 download-filename 头）。
func BuildContentDisposition(realFileName string) (value string, percentName string) {
	percentName = PercentEncode(realFileName)
	ascii := AsciiFilenameFallback(realFileName)
	value = "attachment; filename=\"" + ascii + "\";" + "filename*=utf-8''" + percentName
	return
}

// RealDownloadName 对应 fileDownload 里的 realFileName 生成：currentTimeMillis + 首个 '_' 之后的片段。
func RealDownloadName(fileName string, nowMillis int64) string {
	idx := strings.Index(fileName, "_")
	rest := fileName
	if idx >= 0 {
		rest = fileName[idx+1:]
	}
	return strconv.FormatInt(nowMillis, 10) + rest
}
