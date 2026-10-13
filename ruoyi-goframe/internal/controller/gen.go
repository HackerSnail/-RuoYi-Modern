// Package controller —— 代码生成 /tool/gen（16-A：元数据 CRUD 全链路，不含生成引擎）。
// 逐字对齐 GenController 的 list/getInfo/dbList/columnList/importTableSave/createTableSave/editSave/remove/synchDb。
// 权限与 @Log 在 router 侧经 middleware 收口；preview/download/genCode/batchGenCode 依赖 Velocity 引擎，随 16-B/C 落地。
// createTableSave 的两阶段编排忠实落在 controller（对齐 Java 分层：Druid 缺位改自研切分器，见 service）。
package controller

import (
	"strconv"
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// GenList GET /tool/gen/list → TableDataInfo（已导入生成表，过滤+分页，默认 table_id asc）。
func GenList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.GenTableListFilter{
		TableName:    r.Get("tableName").String(),
		TableComment: r.Get("tableComment").String(),
		BeginTime:    paramRange(r, "beginTime"),
		EndTime:      paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectGenTableListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// GenGetInfo GET /tool/gen/{tableId} → {info:表, rows:列, tables:全部表} 三键 map（编辑页回显 + 主子表选择）。
func GenGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	id, err := parseInt(r.GetRouter("tableId").String())
	if err != nil {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	table, err := service.GetGenTableById(ctx, id)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	tables, err := service.SelectGenTableAll(ctx)
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	columns, err := service.SelectGenTableColumnListByTableId(ctx, id)
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, response.OkData(map[string]interface{}{"info": table, "rows": columns, "tables": tables}))
}

// GenDataList GET /tool/gen/db/list → TableDataInfo（information_schema 可导入实表，排除 qrtz_/gen_/已导入，create_time desc）。
func GenDataList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.GenTableListFilter{
		TableName:    r.Get("tableName").String(),
		TableComment: r.Get("tableComment").String(),
		BeginTime:    paramRange(r, "beginTime"),
		EndTime:      paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectDbTableListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// GenColumnList GET /tool/gen/column/{tableId} → 列 list + total=len（不分页，对齐 Java 手填 TableDataInfo）。
func GenColumnList(r *ghttp.Request) {
	ctx := r.Context()
	id, err := parseInt(r.GetRouter("tableId").String())
	if err != nil {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	columns, err := service.SelectGenTableColumnListByTableId(ctx, id)
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(int64(len(columns)), columns))
}

// ImportTable POST /tool/gen/importTable（tool:gen:import + @Log 代码生成/IMPORT）：
// 表单 tables(逗号串)+tplWebType；空→「请选择要导入的表」；反读实表列→ImportGenTable。
func ImportTable(r *ghttp.Request) {
	ctx := r.Context()
	tableNames := toStrArray(r.Get("tables").String())
	tplWebType := r.Get("tplWebType").String()
	if len(tableNames) == 0 {
		response.Send(r, response.Err("请选择要导入的表"))
		return
	}
	tableList, err := service.SelectDbTableListByNames(ctx, tableNames)
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	if err := service.ImportGenTable(ctx, tableList, tplWebType, currentUsername(r)); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.Ok())
}

// CreateTable POST /tool/gen/createTable（tool:gen:create + @Log 创建表/OTHER）：
// 两阶段——① filterKeyword + 切分收集 CREATE TABLE + 防覆盖 + 逐条建表；② 回读元数据导入生成配置。
// 文案逐字对齐 Java GenController.createTableSave（含「表已建成功 [..]，但导入生成配置失败…」不一致态提示）。
func CreateTable(r *ghttp.Request) {
	ctx := r.Context()
	sql := r.Get("sql").String()
	tplWebType := r.Get("tplWebType").String()

	createdTables := make([]string, 0)
	// 阶段一整体兜底：filterKeyword 抛错或建表异常均归「创建表结构异常」（已建则带回名单）。
	if err := service.FilterKeyword(sql); err != nil {
		response.Send(r, response.Err("创建表结构异常"))
		return
	}
	stmts := service.ExtractCreateStatements(sql)
	if len(stmts) == 0 {
		response.Send(r, response.Err("未解析到有效的 CREATE TABLE 语句"))
		return
	}
	// 防覆盖：逐条取表名，命中同名实表即拒。
	names := make([]string, 0, len(stmts))
	for _, stmt := range stmts {
		name := service.TableNameFromCreate(stmt)
		exist, err := service.CheckTableExist(ctx, name)
		if err != nil {
			response.Send(r, response.Err("创建表结构异常"))
			return
		}
		if exist {
			response.Send(r, response.Err("表 '"+name+"' 已存在，不允许覆盖创建"))
			return
		}
		names = append(names, name)
	}
	// 逐条建表，记录成功名单。
	for _, stmt := range stmts {
		ok, err := service.CreateSingleTable(ctx, stmt)
		if err != nil {
			if len(createdTables) > 0 {
				response.Send(r, response.Err("创建表结构异常，已成功建表 "+javaList(createdTables)+"，请人工核对"))
			} else {
				response.Send(r, response.Err("创建表结构异常"))
			}
			return
		}
		if ok {
			createdTables = append(createdTables, service.TableNameFromCreate(stmt))
		}
	}
	if len(createdTables) == 0 {
		response.Send(r, response.Err("创建表结构失败，未成功建立任何表"))
		return
	}
	// 阶段二：回读并导入生成配置；失败明确暴露「表已建、配置未入库」不一致态。
	tableList, err := service.SelectDbTableListByNames(ctx, createdTables)
	if err == nil {
		err = service.ImportGenTable(ctx, tableList, tplWebType, currentUsername(r))
	}
	if err != nil {
		response.Send(r, response.Err("表已建成功 "+javaList(createdTables)+"，但导入生成配置失败，请人工核对后重试"))
		return
	}
	response.Send(r, response.Ok())
}

// GenEdit PUT /tool/gen（tool:gen:edit + @Log 代码生成/UPDATE）：body 逐字段校验 → ValidateEdit → EditGenTable。
// 忠实对齐 Java editSave：不注入 updateBy（Java 仅 BeanUtils.copy，由前端/请求体决定），文案逐字对齐 DTO 注解。
func GenEdit(r *ghttp.Request) {
	var t model.GenTable
	if err := r.Parse(&t); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateGenEdit(&t); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	if err := service.ValidateEdit(&t); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	if err := service.EditGenTable(r.Context(), &t); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.Ok())
}

// GenRemove DELETE /tool/gen/{tableIds}（tool:gen:remove + @Log 代码生成/DELETE）：逗号拆 ids→删主表+级联列。
func GenRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.GetRouter("tableIds").String())
	if !ok {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	if err := service.RemoveGenTables(ctx, ids); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.Ok())
}

// GenSynchDb GET /tool/gen/synchDb/{tableName}（tool:gen:edit + @Log 代码生成/UPDATE）：以实表为准增量同步列配置。
func GenSynchDb(r *ghttp.Request) {
	ctx := r.Context()
	tableName := r.GetRouter("tableName").String()
	if err := service.SynchDb(ctx, tableName); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.Ok())
}

// GenPreview GET /tool/gen/preview/{tableId}（tool:gen:preview）：渲染各模板返回「模板路径→生成内容」保序对象。
// 逐字对齐 Java GenController.preview：dataMap 为 LinkedHashMap，键序 = getTemplateList 顺序。
func GenPreview(r *ghttp.Request) {
	ctx := r.Context()
	id, err := parseInt(r.GetRouter("tableId").String())
	if err != nil {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	result, err := service.PreviewCode(ctx, id)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.OkData(result))
}

// GenDownload GET /tool/gen/download/{tableName}（tool:gen:code + @Log 代码生成/GENCODE）：
// 单表打包为 zip 写响应流，逐字对齐 Java download（内部 downloadCode(tableName)→writeZipToResponse）。
func GenDownload(r *ghttp.Request) {
	ctx := r.Context()
	tableName := r.GetRouter("tableName").String()
	data, err := service.DownloadCode(ctx, []string{tableName})
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	writeZipToResponse(r, data)
}

// GenCode GET /tool/gen/genCode/{tableName}（tool:gen:code + @Log 代码生成/GENCODE）：自定义路径落盘。
// 对齐 Java genCode：先判 genConfig.isAllowOverwrite()，不允许则直接返回错误文案，不落盘。
func GenCode(r *ghttp.Request) {
	ctx := r.Context()
	if !service.GetGenConfig(ctx).AllowOverwrite {
		response.Send(r, response.Err("【系统预设】不允许生成文件覆盖到本地"))
		return
	}
	tableName := r.GetRouter("tableName").String()
	if err := service.GeneratorCodeToDisk(ctx, tableName); err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, response.Ok())
}

// GenBatchGenCode GET /tool/gen/batchGenCode?tables=（tool:gen:code + @Log 代码生成/GENCODE）：
// 多表合并打包 zip。对齐 Java batchGenCode：tables 拆空即报「请选择要生成的表」（Java 为 ServiceException）。
func GenBatchGenCode(r *ghttp.Request) {
	ctx := r.Context()
	names := toStrArray(r.Get("tables").String())
	if len(names) == 0 {
		response.Send(r, response.Err("请选择要生成的表"))
		return
	}
	data, err := service.DownloadCode(ctx, names)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	writeZipToResponse(r, data)
}

// writeZipToResponse 逐字对齐 Java 同名私有方法：固定文件名 ruoyi.zip（非动态）、跨域暴露头、Content-Length、
// octet-stream，写 zip 字节到响应流。注意：与文件下载的 RFC6266 双轨头不同，此处 Java 写死字面量，原样复刻。
func writeZipToResponse(r *ghttp.Request, data []byte) {
	r.Response.ClearBuffer()
	r.Response.Header().Set("Access-Control-Allow-Origin", "*")
	r.Response.Header().Set("Access-Control-Expose-Headers", "Content-Disposition")
	r.Response.Header().Set("Content-Disposition", "attachment; filename=\"ruoyi.zip\"")
	r.Response.Header().Set("Content-Length", strconv.Itoa(len(data)))
	r.Response.Header().Set("Content-Type", "application/octet-stream; charset=UTF-8")
	r.Response.Write(data)
}

// ---- 辅助 ----

// validateGenEdit 逐字对齐 GenTableEditDTO 的 @NotBlank/@Pattern/@Valid（进 service 前显式收口）。
func validateGenEdit(t *model.GenTable) []violation {
	vs := make([]violation, 0)
	blank := func(field, msg string) {
		if strings.TrimSpace(field) == "" {
			vs = append(vs, violation{Field: fieldOf(msg), Message: msg})
		}
	}
	blank(t.TableName, "表名称不能为空")
	blank(t.TableComment, "表描述不能为空")
	blank(t.ClassName, "实体类名称不能为空")
	blank(t.PackageName, "生成包路径不能为空")
	blank(t.ModuleName, "生成模块名不能为空")
	blank(t.BusinessName, "生成业务名不能为空")
	blank(t.FunctionName, "生成功能名不能为空")
	blank(t.FunctionAuthor, "作者不能为空")
	if !inSet(t.TplCategory, "crud", "tree", "sub") {
		vs = append(vs, violation{Field: "tplCategory", Message: "模板类别非法，应为 crud/tree/sub"})
	}
	if !inSet(t.TplWebType, "element-ui", "element-plus", "element-plus-typescript") {
		vs = append(vs, violation{Field: "tplWebType", Message: "前端类型非法，应为 element-ui/element-plus/element-plus-typescript"})
	}
	if !inSet(t.GenType, "0", "1") {
		vs = append(vs, violation{Field: "genType", Message: "生成方式非法，应为 0(zip压缩包)/1(自定义路径)"})
	}
	for _, c := range t.Columns {
		if strings.TrimSpace(c.JavaField) == "" {
			vs = append(vs, violation{Field: "columns.javaField", Message: "Java属性不能为空"})
		}
	}
	return vs
}

// inSet 判值是否属于给定集合（对齐 @Pattern 枚举式白名单）。
func inSet(v string, set ...string) bool {
	for _, s := range set {
		if v == s {
			return true
		}
	}
	return false
}

// fieldOf 由「X不能为空」文案回推字段名（仅用于校验明细的 field 键，前端非必需，尽量贴合 DTO 字段）。
func fieldOf(msg string) string {
	name := strings.TrimSuffix(msg, "不能为空")
	switch name {
	case "表名称":
		return "tableName"
	case "表描述":
		return "tableComment"
	case "实体类名称":
		return "className"
	case "生成包路径":
		return "packageName"
	case "生成模块名":
		return "moduleName"
	case "生成业务名":
		return "businessName"
	case "生成功能名":
		return "functionName"
	case "作者":
		return "functionAuthor"
	default:
		return name
	}
}

// toStrArray 复刻 Convert.toStrArray：空串→nil；否则按 ',' 拆分并去除尾部空元素（对齐 Java String.split 语义，不 trim）。
func toStrArray(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	for len(parts) > 0 && parts[len(parts)-1] == "" {
		parts = parts[:len(parts)-1]
	}
	return parts
}

// javaList 复刻 ArrayList.toString()：形如 "[a, b]"（逗号+空格），供 createTable 异常文案对齐 Java。
func javaList(items []string) string {
	return "[" + strings.Join(items, ", ") + "]"
}

// currentUsername 取当前登录用户名（对齐 SecurityUtils.getUsername）；无会话回空串。
func currentUsername(r *ghttp.Request) string {
	if lu := middleware.CurrentLoginUser(r); lu != nil {
		return lu.Username
	}
	return ""
}
