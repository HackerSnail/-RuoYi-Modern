// Package middleware —— 操作日志写入基建，逐条复刻 com.ruoyi.framework.aspectj.LogAspect 的 @Around 语义。
//
// 契约要点（不得错位）：
//   - 挂载形态：Go 无注解/AOP，用装饰器 Log(meta, handler)，且必须嵌在 Permi 内侧
//     （Permi(perm, Log(meta, ctrl))）。理由：Java @PreAuthorize 拒绝时请求到不了 controller、@Around 不触发→
//     越权请求不记日志；故 Log 只圈住真正执行的 handler。未挂 @Log 的端点保持裸注册、不记日志。
//   - 同步快照 + 异步插入：GF 的 *Request/Response 缓冲是对象池复用的，异步协程绝不能触碰 r。
//     在 handler 返回后同步把全部字段快照进 model.SysOperLog，再 gctx.New() 起协程只做 DB 插入；
//     插入/序列化自身失败仅 glog 本地兜底、绝不影响业务响应（对齐 handleLog 外层 try-catch + recordOper）。
//   - operParam：query 为空且方法 ∈ {PUT,POST,DELETE} → 取请求 body；否则取 query map。JSON 解析后脱敏截断 2000；
//     非 JSON（multipart/二进制）留空（对齐 Java isFilterObject 跳过 MultipartFile）。
//   - jsonResult：读 r.Response.Buffer()（已写出的响应体），非 JSON 则跳过；脱敏截断 2000。
//   - 脱敏：EXCLUDE_PROPERTIES 整字段剔除凭据 + PII 值级部分脱敏（phone/idcard/bankcard/email）。
//   - status：Go 无 Java 异常抛出层，业务失败统一以响应体 code≠200 表达（非 panic）。据用户确认口径：
//     code≠200 → FAIL 且 errorMsg=响应 msg；code==200 → SUCCESS。handler panic 亦记 FAIL（errorMsg=panic 文本）。
//   - oper_location：留空。Java 走 AddressUtils 在线 IP→地址查询，Go 侧对 logininfor 已确立"不复刻在线库、留空"
//     的一致简化，此处沿用。
package middleware

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/os/gctx"
	"github.com/gogf/gf/v2/os/glog"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// OperLogMeta 是 @Log 注解的等价元数据（title / businessType / operatorType / method / save 开关 / exclude 名单）。
type OperLogMeta struct {
	Title         string
	BusinessType  int
	OperatorType  int
	Method        string // 代码单元标识，对齐 Java "类.方法()" 的意图（Go 侧用 controller.HandlerName）
	SaveRequest   bool
	SaveResponse  bool
	ExcludeParams []string
}

// OpLog 构造默认元数据：operatorType=MANAGE、保存请求与响应、无额外排除项（对齐 @Log 注解默认值）。
func OpLog(title string, businessType int, method string) OperLogMeta {
	return OperLogMeta{
		Title:        title,
		BusinessType: businessType,
		OperatorType: consts.OpTypeManage,
		Method:       method,
		SaveRequest:  true,
		SaveResponse: true,
	}
}

// ---- 脱敏引擎（纯函数，复刻 LogAspect，可无 DB 单测）----

// opLogCredentialKeys：整字段剔除的凭据类属性名（均小写，比对 toLowerCase）。对齐 EXCLUDE_PROPERTIES。
var opLogCredentialKeys = map[string]bool{
	"password": true, "oldpassword": true, "newpassword": true, "confirmpassword": true,
	"token": true, "captcha": true, "smscode": true, "verifycode": true,
	"accesstoken": true, "refreshtoken": true,
}

// PII 值级部分脱敏名单（均小写）：与凭据类分工——PII 保留可读、仅部分打码。
var (
	opLogPhoneKeys   = map[string]bool{"phone": true, "phonenumber": true, "mobile": true, "mobileno": true, "mobilephone": true, "tel": true, "telephone": true, "contactphone": true}
	opLogIdCardKeys  = map[string]bool{"idcard": true, "idcardno": true, "idnumber": true, "idno": true, "identitycard": true, "certno": true, "certificateno": true, "sensateidcard": true}
	opLogEmailKeys   = map[string]bool{"email": true, "mail": true, "emailaddress": true, "useremail": true}
	opLogBankCardKeys = map[string]bool{"bankcard": true, "bankcardno": true, "bankno": true, "cardno": true, "bankaccount": true, "accountno": true, "bankaccountno": true}
)

// opLogMaskValue 按属性名归类对 PII 值做部分脱敏；非命中名单原样返回。
func opLogMaskValue(name string, v string) string {
	key := strings.ToLower(name)
	switch {
	case opLogPhoneKeys[key]:
		return opLogMaskMiddle(v, 3, 4)
	case opLogIdCardKeys[key]:
		return opLogMaskMiddle(v, 4, 4)
	case opLogBankCardKeys[key]:
		return opLogMaskMiddle(v, 0, 4)
	case opLogEmailKeys[key]:
		return opLogMaskEmail(v)
	default:
		return v
	}
}

// opLogMaskMiddle 保留头 head 位与尾 tail 位、中间以等长星号替换；长度≤head+tail 时整体打码不外泄。
func opLogMaskMiddle(s string, head, tail int) string {
	r := []rune(s)
	if len(r) <= head+tail {
		return strings.Repeat("*", len(r))
	}
	var b strings.Builder
	b.WriteString(string(r[:head]))
	b.WriteString(strings.Repeat("*", len(r)-head-tail))
	if tail > 0 {
		b.WriteString(string(r[len(r)-tail:]))
	}
	return b.String()
}

// opLogMaskEmail 邮箱保留首字符与域名(a***@domain)；无 @ 则退化为只留首字符。
func opLogMaskEmail(s string) string {
	at := strings.IndexByte(s, '@')
	if at <= 0 {
		return opLogMaskMiddle(s, 1, 0)
	}
	return string(s[0]) + "***" + s[at:]
}

// opLogMaskAny 递归遍历解析后的 JSON：map 命中凭据键则整字段剔除、否则递归；slice 逐元素按同名脱敏；string 按键名打码。
func opLogMaskAny(key string, v interface{}, ex map[string]bool) interface{} {
	switch t := v.(type) {
	case map[string]interface{}:
		out := make(map[string]interface{}, len(t))
		for k, val := range t {
			if ex[k] || opLogCredentialKeys[strings.ToLower(k)] {
				continue
			}
			out[k] = opLogMaskAny(k, val, ex)
		}
		return out
	case []interface{}:
		out := make([]interface{}, 0, len(t))
		for _, e := range t {
			out = append(out, opLogMaskAny(key, e, ex))
		}
		return out
	case string:
		return opLogMaskValue(key, t)
	default:
		return v
	}
}

// opLogMarshalMasked 把已脱敏的 JSON 值序列化（禁用 HTML 转义以保持可读），失败返回空串。
func opLogMarshalMasked(v interface{}) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return ""
	}
	return strings.TrimSpace(buf.String())
}

// opLogTruncate 按 rune 截断（对齐 Java 字符级 substring，避免多字节被劈半）。
func opLogTruncate(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	return string([]rune(s)[:max])
}

// opLogCap trim + 按列宽截断（对齐 AsyncFactory.cap：入库前 trim 并按目标列宽裁剪）。
func opLogCap(s string, max int) string {
	return opLogTruncate(strings.TrimSpace(s), max)
}

// ---- 采集 ----

// opLogCaptureParam 复刻 setRequestValue：query 空且 PUT/POST/DELETE → body；否则 query map。脱敏后截断 2000。
func opLogCaptureParam(r *ghttp.Request, ex map[string]bool) string {
	method := r.Method
	queryMap := r.GetQueryMap()
	if len(queryMap) == 0 && (method == "PUT" || method == "POST" || method == "DELETE") {
		body := r.GetBody()
		if len(body) == 0 {
			return ""
		}
		var v interface{}
		if err := json.Unmarshal(body, &v); err != nil {
			return "" // 非 JSON（multipart/二进制）→ 留空，对齐 isFilterObject
		}
		return opLogTruncate(opLogMarshalMasked(opLogMaskAny("", v, ex)), consts.OpLogParamMaxLen)
	}
	if len(queryMap) == 0 {
		return ""
	}
	m := make(map[string]interface{}, len(queryMap))
	for k, val := range queryMap {
		m[k] = val
	}
	return opLogTruncate(opLogMarshalMasked(opLogMaskAny("", m, ex)), consts.OpLogParamMaxLen)
}

// opLogCaptureResult 复刻响应保存：读已写出缓冲，非 JSON 跳过；脱敏后截断 2000。
// 同时解析出 code/msg 用于 status 判定（Go 侧业务失败=code≠200）。
func opLogCaptureResult(r *ghttp.Request, ex map[string]bool) (jsonResult string, code int, msg string) {
	buf := r.Response.Buffer()
	if len(buf) == 0 {
		return "", 0, ""
	}
	var v interface{}
	if err := json.Unmarshal(buf, &v); err != nil {
		return "", 0, "" // 二进制响应（导出/下载）不记 jsonResult
	}
	if m, ok := v.(map[string]interface{}); ok {
		if c, ok := m["code"].(float64); ok {
			code = int(c)
		}
		if s, ok := m["msg"].(string); ok {
			msg = s
		}
	}
	return opLogTruncate(opLogMarshalMasked(opLogMaskAny("", v, ex)), consts.OpLogParamMaxLen), code, msg
}

// ---- 装饰器 ----

// Log 将操作日志采集与 @Log 元数据组合成单个 handler，供 route 注册（通常嵌在 Permi 内侧）。
// 语义对齐 LogAspect.around：记耗时 → 执行 handler → 采集响应/异常 → 异步落库；本装饰器吞 panic 前先落库再上抛（保住 500 语义）。
func Log(meta OperLogMeta, next ghttp.HandlerFunc) ghttp.HandlerFunc {
	return func(r *ghttp.Request) {
		start := time.Now()
		defer func() {
			if rec := recover(); rec != nil {
				// panic 路径：以 panic 文本作 errorMsg 记 FAIL，落库后重新上抛交 GF 全局恢复回 500。
				opLogRecord(r, meta, start, rec)
				panic(rec)
			}
		}()
		next(r)
		opLogRecord(r, meta, start, nil)
	}
}

// opLogRecord 全程防御式：任何异常只 glog 本地兜底、绝不外抛影响响应（对齐 handleLog 外层 try-catch）。
// panicVal 非 nil 时按 panic 记 FAIL；否则据响应体 code 判定：code≠0 且 code≠200 → FAIL 且 errorMsg=响应 msg。
func opLogRecord(r *ghttp.Request, meta OperLogMeta, start time.Time, panicVal interface{}) {
	defer func() {
		if e := recover(); e != nil {
			glog.Errorf(r.Context(), "记录操作日志异常: %v", e)
		}
	}()

	ex := make(map[string]bool, len(meta.ExcludeParams))
	for _, k := range meta.ExcludeParams {
		ex[k] = true
	}

	costTime := int(time.Since(start).Milliseconds())
	snap := model.SysOperLog{
		Title:         opLogCap(meta.Title, consts.OpLogTitleLen),
		BusinessType:  meta.BusinessType,
		Method:        opLogCap(meta.Method, consts.OpLogMethodLen),
		RequestMethod: opLogCap(r.Method, consts.OpLogReqMethodLen),
		OperatorType:  meta.OperatorType,
		OperUrl:       opLogTruncate(r.URL.Path, consts.OpLogUrlLen),
		OperIp:        opLogCap(r.GetClientIp(), consts.OpLogOperIpLen),
		Status:        consts.OpStatusSuccess,
		CostTime:      costTime,
		// OperLocation 留空：不复刻在线 IP→地址查询（与 logininfor 一致简化）。
	}

	if lu := CurrentLoginUser(r); lu != nil {
		snap.OperName = opLogCap(lu.Username, consts.OpLogOperNameLen)
		if lu.User != nil && lu.User.Dept != nil {
			snap.DeptName = opLogCap(lu.User.Dept.DeptName, consts.OpLogDeptNameLen)
		}
	}

	if meta.SaveRequest {
		snap.OperParam = opLogCaptureParam(r, ex)
	}

	var bizCode int
	var bizMsg string
	if meta.SaveResponse {
		snap.JsonResult, bizCode, bizMsg = opLogCaptureResult(r, ex)
	}

	// status/errorMsg 判定：panic 优先；否则按业务码。成功时 errorMsg 保持空。
	if panicVal != nil {
		snap.Status = consts.OpStatusFail
		snap.ErrorMsg = opLogTruncate(fmt.Sprintf("%v", panicVal), consts.OpLogErrorMsgLen)
		snap.JsonResult = ""
	} else if bizCode != 0 && bizCode != consts.CodeSuccess {
		snap.Status = consts.OpStatusFail
		snap.ErrorMsg = opLogTruncate(bizMsg, consts.OpLogErrorMsgLen)
	}

	// 同步快照已完成，异步协程只用快照 + 独立 ctx（请求 ctx 返回后即被取消）。
	go func(o model.SysOperLog) {
		defer func() {
			if e := recover(); e != nil {
				glog.Errorf(gctx.New(), "异步写入操作日志异常: %v", e)
			}
		}()
		ctx := gctx.New()
		if err := service.InsertOperLog(ctx, &o); err != nil {
			glog.Errorf(ctx, "异步写入操作日志失败: %v", err)
		}
	}(snap)
}
