/**
 * 请求失败的语义解析：纯函数、零依赖。
 *
 * 后端已把业务失败映射为真实 HTTP 状态码（`HttpStatusResponseAdvice`：响应体 code=500 → HTTP 400、
 * 认证失败 → HTTP 401、权限不足 → HTTP 403），而"HTTP 200 + 体里 code"的旧约定只在关掉该开关
 * （`ruoyi.http-status-advice.enabled=false`）时才成立。两条路径如果各写一套判断，文案与动作迟早分叉，
 * 因此统一收敛到本文件：调用方只按返回的 action 执行弹窗/提示，不自己猜状态码。
 *
 * 这里刻意不引入 element-plus 与 errorCode 表：保持零依赖才能用 node 直接跑断言验证各分支；
 * 按码的通用文案由调用方以 fallbackText(code) 注入。
 */

/** 处置动作：调用方据此选择提示方式 */
export const ERROR_ACTION = {
  /** 会话过期：弹「重新登录」确认 */
  RELOGIN: 'relogin',
  /** 业务失败：红色 toast 展示后端文案 */
  MESSAGE_ERROR: 'messageError',
  /** 需提醒但不算操作失败（code=601）：黄色 toast */
  MESSAGE_WARNING: 'messageWarning',
  /** 连接层失败（网络异常 / 超时 / 请求被取消） */
  NETWORK: 'network',
  /** 其余非成功码（403/404 等）：右上角通知条 */
  NOTIFY: 'notify',
  /**
   * 二进制请求（responseType=blob/arraybuffer）失败：响应体是 Blob，本模块同步读不出 code/msg。
   * 此时拦截器一律不代言（否则只能按 HTTP 状态码给"系统未知错误"这类假文案，还会与下载方自己
   * 的提示叠成两条），只把原始 error 拒出去，由调用方 await 读出后端 msg 后再提示。
   */
  BINARY: 'binary'
}

/** 会话过期的固定话术（既用于拒绝值，也保持与各页面 .catch 原有判断一致） */
const UNAUTHORIZED_MESSAGE = '无效的会话，或者会话已过期，请重新登录。'
/** 传输层无响应时的通用文案（这些串由 axios 产生，不在 errorCode 表里） */
const NETWORK_ERROR_MESSAGE = '后端接口连接异常'
const TIMEOUT_MESSAGE = '系统接口请求超时'
const DEFAULT_FALLBACK_MESSAGE = '系统未知错误，请反馈给管理员'

/**
 * 按业务码决定处置方式。
 *
 * @param {number} code 业务码（响应体 code，缺失时传 HTTP 状态码）
 * @param {string} msg 后端返回的用户可读文案，优先于按码的通用文案
 * @param {(code: number) => string} [fallbackText] 按码取通用文案
 * @returns {{action: string, text: string}}
 */
export function resolveBusinessFailure(code, msg, fallbackText) {
  if (code === 401) {
    return { action: ERROR_ACTION.RELOGIN, text: UNAUTHORIZED_MESSAGE }
  }
  const text = pickText(msg, code, fallbackText)
  if (code === 500) {
    return { action: ERROR_ACTION.MESSAGE_ERROR, text }
  }
  if (code === 601) {
    return { action: ERROR_ACTION.MESSAGE_WARNING, text }
  }
  return { action: ERROR_ACTION.NOTIFY, text }
}

/**
 * 解析 axios 抛出的请求错误（真实 HTTP 4xx/5xx，或压根没响应）。
 *
 * 有响应体时以体里的 code/msg 为准：后端把业务失败打成 HTTP 400 只是传输层语义，
 * 真正的"是什么失败"仍写在体内；体里没有 code 才退回用状态码定档。
 *
 * @param {object} error axios 的错误对象
 * @param {(code: number) => string} [fallbackText] 按码取通用文案
 * @returns {{action: string, text: string}}
 */
export function resolveRequestFailure(error, fallbackText) {
  const response = error && error.response
  if (!response) {
    return { action: ERROR_ACTION.NETWORK, text: resolveTransportText(error) }
  }
  // 下载类请求的失败体是 Blob，同步路径读不出业务码，交调用方异步解析（见 readFailureMessage）
  if (isBinaryRequest(error)) {
    return { action: ERROR_ACTION.BINARY, text: '' }
  }
  const body = readBody(response)
  const code = typeof body.code === 'number' ? body.code : response.status
  const msg = typeof body.msg === 'string' ? body.msg : ''
  return resolveBusinessFailure(code, msg, fallbackText)
}

/**
 * 是否为二进制请求（导出/下载）。
 *
 * @param {object} error axios 的错误对象
 * @returns {boolean}
 */
export function isBinaryRequest(error) {
  const responseType = error && error.config && error.config.responseType
  return responseType === 'blob' || responseType === 'arraybuffer'
}

/**
 * 异步读出失败响应里的用户可读文案。
 *
 * 后端把业务失败映射成真实 HTTP 状态码，但"到底为什么失败"仍写在响应体的 code/msg 里；
 * 下载请求因为 responseType=blob 拿到的是 Blob，只有这里能 await 出来。体读不出 JSON
 * （网关返回的 HTML 错误页等）时退回按状态码取通用文案，绝不返回空串导致提示空白。
 *
 * @param {object} error axios 的错误对象；也接受 `{ response: { status, data } }` 形态的
 *                       伪错误——关掉状态码改写时下载失败仍是 HTTP 200 + JSON 体，可复用本函数
 * @param {(code: number) => string} [fallbackText] 按码取通用文案
 * @returns {Promise<string>}
 */
export async function readFailureMessage(error, fallbackText) {
  const response = error && error.response
  if (!response) {
    return resolveTransportText(error)
  }
  const body = await readBodyAsync(response.data)
  const code = typeof body.code === 'number' ? body.code : response.status
  const msg = typeof body.msg === 'string' ? body.msg : ''
  return pickText(msg, code, fallbackText)
}

/**
 * 文案取舍：后端 msg 是面向用户的明确说明（如"没有权限，请联系管理员授权"），
 * 优先于按码的通用文案；两者都拿不到才给兜底串，避免出现空提示。
 */
function pickText(msg, code, fallbackText) {
  if (msg) {
    return msg
  }
  const fallback = typeof fallbackText === 'function' ? fallbackText(code) : ''
  return fallback || DEFAULT_FALLBACK_MESSAGE
}

/**
 * 无响应（连接失败/超时）时的文案。只在确实拿不到响应体时才用"系统接口NNN异常"这类笼统说法，
 * 业务文案一律走响应体，不再被状态码盖掉。
 */
function resolveTransportText(error) {
  const message = (error && error.message) || ''
  if (message === 'Network Error') {
    return NETWORK_ERROR_MESSAGE
  }
  if (message.includes('timeout')) {
    return TIMEOUT_MESSAGE
  }
  const matched = /Request failed with status code (\d{3})/.exec(message)
  if (matched) {
    return `系统接口${matched[1]}异常`
  }
  return message || DEFAULT_FALLBACK_MESSAGE
}

/**
 * 尽力读出响应体：体是字符串（后端未走 JSON 内容协商，或已由 text() 解出）时尝试解析，解析不了就当作没有。
 */
function readBody(response) {
  return parseBody(response.data)
}

/**
 * 异步版本：Blob/ArrayBuffer 需要先 text() 再解析，供 readFailureMessage 使用。
 */
async function readBodyAsync(data) {
  if (data && typeof data.text === 'function') {
    try {
      return parseBody(await data.text())
    } catch {
      // 读不出文本（如流已被消费）就当作没有体，退回按状态码定档
      return {}
    }
  }
  return parseBody(data)
}

/**
 * 把各种形态的响应体归一成对象。解析失败一律按“无体”处理，不能让 JSON.parse 的异常冒到调用方
 * （下载已经失败，再抛一次异常就成了双重失败，调用方的提示也会被顶掉）。
 */
function parseBody(data) {
  if (typeof data === 'string') {
    return tryParseJson(data)
  }
  if (data === null || typeof data !== 'object') {
    return {}
  }
  return data
}

function tryParseJson(text) {
  try {
    const parsed = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}
