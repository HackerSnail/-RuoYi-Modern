import axios from 'axios'
import { ElNotification , ElMessageBox, ElMessage, ElLoading } from 'element-plus'
import { getToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from '@/utils/ruoyi'
import cache from '@/plugins/cache'
import { saveAs } from 'file-saver'
import useUserStore from '@/store/modules/user'
import { ERROR_ACTION, readFailureMessage, resolveBusinessFailure, resolveRequestFailure } from '@/utils/errorResolver'

let downloadLoadingInstance
// 是否显示重新登录
export let isRelogin = { show: false }

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: import.meta.env.VITE_APP_BASE_API,
  // 超时
  timeout: 10000
})

// request拦截器
service.interceptors.request.use(config => {
  // 是否需要设置 token
  const isToken = (config.headers || {}).isToken === false
  // 是否需要防止数据重复提交
  const isRepeatSubmit = (config.headers || {}).repeatSubmit === false
  // 间隔时间(ms)，小于此时间视为重复提交
  const interval = (config.headers || {}).interval || 1000
  if (getToken() && !isToken) {
    config.headers['Authorization'] = 'Bearer ' + getToken() // 让每个请求携带自定义token 请根据实际情况自行修改
  }
  // get请求映射params参数
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
    const requestObj = {
      url: config.url,
      data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
      time: new Date().getTime()
    }
    const requestSize = Object.keys(JSON.stringify(requestObj)).length // 请求数据大小
    const limitSize = 5 * 1024 * 1024 // 限制存放数据5M
    if (requestSize >= limitSize) {
      console.warn(`[${config.url}]: ` + '请求数据大小超出允许的5M限制，无法进行防重复提交验证。')
      return config
    }
    const sessionObj = cache.session.getJSON('sessionObj')
    if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
      cache.session.setJSON('sessionObj', requestObj)
    } else {
      const s_url = sessionObj.url                // 请求地址
      const s_data = sessionObj.data              // 请求数据
      const s_time = sessionObj.time              // 请求时间
      if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
        const message = '数据正在处理，请勿重复提交'
        console.warn(`[${s_url}]: ` + message)
        return Promise.reject(new Error(message))
      } else {
        cache.session.setJSON('sessionObj', requestObj)
      }
    }
  }
  return config
}, error => {
    console.log(error)
    Promise.reject(error)
})

// 按码取通用文案（后端未给出可读 msg 时才用），注入给零依赖的 errorResolver
const fallbackText = code => errorCode[code] || errorCode['default']

// 会话过期确认框。“2xx + 体里 code=401”与“真实 HTTP 401”两条路径共用同一份，避免复制一遍弹窗逻辑
function handleUnauthorized() {
  if (isRelogin.show) {
    return
  }
  isRelogin.show = true
  ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', { confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning' }).then(() => {
    isRelogin.show = false
    useUserStore().logOut().then(() => {
      location.href = '/index'
    })
  }).catch(() => {
    isRelogin.show = false
  })
}

// 执行 errorResolver 判出的处置动作。各分支的拒绝值类型与改造前逐项对齐（字符串 / Error / 'error'），
// 页面里既有的 .catch(() => {}) 与直接把 err 丢给提示的写法都不受影响
function dispatchFailure({ action, text }) {
  switch (action) {
    case ERROR_ACTION.RELOGIN:
      handleUnauthorized()
      return Promise.reject(text)
    case ERROR_ACTION.MESSAGE_ERROR:
      ElMessage({ message: text, type: 'error' })
      return Promise.reject(new Error(text))
    case ERROR_ACTION.MESSAGE_WARNING:
      ElMessage({ message: text, type: 'warning' })
      return Promise.reject(new Error(text))
    default:
      ElNotification.error({ title: text })
      return Promise.reject('error')
  }
}

// 响应拦截器
service.interceptors.response.use(res => {
    // 二进制数据则直接返回（导出/下载的失败由 download 自己按响应体判定）
    if (res.request.responseType ===  'blob' || res.request.responseType ===  'arraybuffer') {
      return res.data
    }
    // 未设置状态码则默认成功状态
    const code = res.data.code || 200
    // 关掉后端状态码改写（ruoyi.http-status-advice.enabled=false）时，业务失败仍是 HTTP 200 + 体里 code，
    // 与下面的 reject 分支语义相同，故共用 errorResolver，不再各写一套判定与文案
    if (code !== 200) {
      return dispatchFailure(resolveBusinessFailure(code, res.data.msg, fallbackText))
    }
    return  Promise.resolve(res.data)
  },
  error => {
    // 后端已把业务失败映射成真实 HTTP 状态码，故失败一律先进本分支；
    // 判定交给 errorResolver：有响应体时按体内 code/msg 走，拿不到响应才按传输层定档
    const failure = resolveRequestFailure(error, fallbackText)
    if (failure.action === ERROR_ACTION.BINARY) {
      // 导出/下载失败：体内是 Blob，这里弹不出真文案，只回拒原始 error 给 download 自己解析
      return Promise.reject(error)
    }
    if (failure.action === ERROR_ACTION.NETWORK) {
      console.log('err' + error)
      ElMessage({ message: failure.text, type: 'error', duration: 5 * 1000 })
      return Promise.reject(error)
    }
    return dispatchFailure(failure)
  }
)

/**
 * 展示一次请求失败的文案：异步从响应体（含 Blob）里读出后端 msg，读不出才按状态码取通用文案。
 *
 * 导出给裸 axios 的下载调用方复用（见 @/plugins/download）：它们绕过了响应拦截器，
 * 严格后端下任何 4xx 都会落到这里，各处再各写一套 errorCode[code] || msg 就必然分叉。
 *
 * @param {object} error axios 错误对象，或 `{ response: { status, data } }` 形态的伪错误
 */
export async function reportFailure(error) {
  ElMessage.error(await readFailureMessage(error, fallbackText))
}

// 通用下载方法
export function download(url, params, filename, config) {
  downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
  return service.post(url, params, {
    transformRequest: [(params) => { return tansParams(params) }],
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    responseType: 'blob',
    ...config
  }).then(async (data) => {
    if (blobValidate(data)) {
      saveAs(new Blob([data]), filename)
    } else {
      // 关掉状态码改写（ruoyi.http-status-advice.enabled=false）时，失败仍是 HTTP 200 + JSON 体，
      // 伪造成 errorResolver 认识的响应形态，与下面 catch 共用同一个文案出口
      await reportFailure({ response: { status: 200, data } })
    }
    downloadLoadingInstance.close()
  }).catch(async (r) => {
    console.error(r)
    // 拦截器对二进制请求刻意不代言，真文案在此从 Blob 体里读；读不出才退化
    await reportFailure(r)
    downloadLoadingInstance.close()
  })
}

export default service
