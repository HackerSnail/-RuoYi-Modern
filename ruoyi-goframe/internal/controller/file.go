// Package controller —— /common 通用上传下载。对齐 CommonController：
// upload/uploads 落盘并回虚拟路径，download/resource 流式回传。均无 @PreAuthorize（仅需登录），
// 挂在 Auth 分组内。下载类接口 Java 是 void + try/catch{log}，故非法/缺失一律“静默 200 空体”，
// 不回错误 JSON——本文件严格复刻该行为（记日志后直接 return，不写任何字节）。
package controller

import (
	"strings"
	"time"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/service"
)

// serverURL 对应 ServerConfig.getUrl()：scheme://host + contextPath("/")。
// contextPath 为 "/"，而 fileName 自带前导 "/"，故拼接结果含双斜杠——此为 Java 原样行为，忠实保留。
func serverURL(r *ghttp.Request) string {
	scheme := "http"
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = p
	} else if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host + "/"
}

// FileUpload 对应 POST /common/upload（单文件，参数名 file）。
func FileUpload(r *ghttp.Request) {
	files := r.GetMultipartFiles("file")
	if len(files) == 0 {
		api.Send(r, api.Err("上传文件不能为空"))
		return
	}
	fileName, originalName, err := service.Upload(r.Context(), service.GetUploadPath(r.Context()), files[0], defaultUploadAllowed())
	if err != nil {
		g.Log().Errorf(r.Context(), "通用上传失败: %v", err)
		api.Send(r, api.Err(err.Error()))
		return
	}
	m := api.Ok()
	m["url"] = serverURL(r) + fileName
	m["fileName"] = fileName
	m["newFileName"] = service.GetName(fileName)
	m["originalFilename"] = originalName
	api.Send(r, m)
}

// FileUploads 对应 POST /common/uploads（多文件，参数名 files）；任一文件失败即整体报错（对齐 Java 单 try 包裹循环）。
func FileUploads(r *ghttp.Request) {
	files := r.GetMultipartFiles("files")
	if len(files) == 0 {
		api.Send(r, api.Err("上传文件不能为空"))
		return
	}
	baseDir := service.GetUploadPath(r.Context())
	urls, fileNames, newFileNames, originalFilenames := make([]string, 0, len(files)), make([]string, 0, len(files)), make([]string, 0, len(files)), make([]string, 0, len(files))
	for _, fh := range files {
		fileName, originalName, err := service.Upload(r.Context(), baseDir, fh, defaultUploadAllowed())
		if err != nil {
			g.Log().Errorf(r.Context(), "通用上传失败: %v", err)
			api.Send(r, api.Err(err.Error()))
			return
		}
		urls = append(urls, serverURL(r)+fileName)
		fileNames = append(fileNames, fileName)
		newFileNames = append(newFileNames, service.GetName(fileName))
		originalFilenames = append(originalFilenames, originalName)
	}
	m := api.Ok()
	m["urls"] = strings.Join(urls, ",")
	m["fileNames"] = strings.Join(fileNames, ",")
	m["newFileNames"] = strings.Join(newFileNames, ",")
	m["originalFilenames"] = strings.Join(originalFilenames, ",")
	api.Send(r, m)
}

// FileDownload 对应 GET /common/download（从 downloadPath 取文件，可选删源）。
func FileDownload(r *ghttp.Request) {
	fileName := r.Get("fileName").String()
	del := r.Get("delete").Bool()
	ctx := r.Context()
	if !service.CheckAllowDownload(fileName) {
		g.Log().Errorf(ctx, "下载文件失败：文件名称(%s)非法，不允许下载。", fileName)
		return
	}
	downloadDir := service.GetDownloadPath(ctx)
	filePath := downloadDir + fileName
	if ok, err := service.IsPathUnderBase(downloadDir, filePath); err != nil || !ok {
		g.Log().Errorf(ctx, "下载文件失败：文件名称(%s)非法，不允许下载。", fileName)
		return
	}
	realFileName := service.RealDownloadName(fileName, time.Now().UnixMilli())
	r.Response.Header().Set("Content-Type", "application/octet-stream")
	setAttachmentHeader(r, realFileName)
	if err := service.WriteFileTo(r.Response.BufferWriter, filePath); err != nil {
		g.Log().Errorf(ctx, "下载文件失败: %v", err)
		return
	}
	if del {
		service.DeleteFileIfExists(filePath)
	}
}

// ResourceDownload 对应 GET /common/download/resource（从 profile 根按 /profile 前缀剥离后取本地资源）。
func ResourceDownload(r *ghttp.Request) {
	resource := r.Get("resource").String()
	ctx := r.Context()
	if !service.CheckAllowDownload(resource) {
		g.Log().Errorf(ctx, "下载文件失败：资源文件(%s)非法，不允许下载。", resource)
		return
	}
	localPath := service.GetProfile(ctx)
	downloadPath := localPath + service.StripPrefix(resource)
	if ok, err := service.IsPathUnderBase(localPath, downloadPath); err != nil || !ok {
		g.Log().Errorf(ctx, "下载文件失败：资源文件(%s)非法，不允许下载。", resource)
		return
	}
	downloadName := afterLastSlash(downloadPath)
	r.Response.Header().Set("Content-Type", "application/octet-stream")
	setAttachmentHeader(r, downloadName)
	if err := service.WriteFileTo(r.Response.BufferWriter, downloadPath); err != nil {
		g.Log().Errorf(ctx, "下载文件失败: %v", err)
		return
	}
}

// defaultUploadAllowed 通用上传允许的后缀集（对应 MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION）。
func defaultUploadAllowed() []string { return service.DefaultAllowedExtension }

// setAttachmentHeader 写出 RFC6266 双轨 attachment 头 + 暴露头 + download-filename 单独回传。
func setAttachmentHeader(r *ghttp.Request, realFileName string) {
	value, percentName := service.BuildContentDisposition(realFileName)
	r.Response.Header().Set("Access-Control-Expose-Headers", "Content-Disposition,download-filename")
	r.Response.Header().Set("Content-Disposition", value)
	r.Response.Header().Set("download-filename", percentName)
}

// afterLastSlash 取最后一个 '/' 之后的片段；不含 '/' 返回空串（对齐 StringUtils.substringAfterLast）。
func afterLastSlash(s string) string {
	i := strings.LastIndex(s, "/")
	if i < 0 {
		return ""
	}
	return s[i+1:]
}
