import axios from 'axios'
import { ElLoading } from 'element-plus'
import { saveAs } from 'file-saver'
import { getToken } from '@/utils/auth'
import { blobValidate } from '@/utils/ruoyi'
import { reportFailure } from '@/utils/request'

const baseURL = import.meta.env.VITE_APP_BASE_API
let downloadLoadingInstance

export default {
  name(name, isDelete = true) {
    var url = baseURL + "/common/download?fileName=" + encodeURIComponent(name) + "&delete=" + isDelete
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data)
      if (isBlob) {
        const blob = new Blob([res.data])
        this.saveAs(blob, decodeURIComponent(res.headers['download-filename']))
      } else {
        this.printErrMsg(res.data)
      }
    }).catch((r) => {
      // 本文件用裸 axios，完全绕过响应拦截器：后端把失败打成真实 4xx 时这里不收尾就静默失败
      console.error(r)
      reportFailure(r)
    })
  },
  resource(resource) {
    var url = baseURL + "/common/download/resource?resource=" + encodeURIComponent(resource)
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data)
      if (isBlob) {
        const blob = new Blob([res.data])
        this.saveAs(blob, decodeURIComponent(res.headers['download-filename']))
      } else {
        this.printErrMsg(res.data)
      }
    }).catch((r) => {
      console.error(r)
      reportFailure(r)
    })
  },
  zip(url, name) {
    var url = baseURL + url
    downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
    axios({
      method: 'get',
      url: url,
      responseType: 'blob',
      headers: { 'Authorization': 'Bearer ' + getToken() }
    }).then((res) => {
      const isBlob = blobValidate(res.data)
      if (isBlob) {
        const blob = new Blob([res.data], { type: 'application/zip' })
        this.saveAs(blob, name)
      } else {
        this.printErrMsg(res.data)
      }
      downloadLoadingInstance.close()
    }).catch((r) => {
      console.error(r)
      reportFailure(r)
      downloadLoadingInstance.close()
    })
  },
  saveAs(text, name, opts) {
    saveAs(text, name, opts)
  },
  /**
   * “请求成功但响应体是 JSON”的下载失败（关掉状态码改写时的形态）：伪造成 errorResolver
   * 认识的响应形态交给 reportFailure，与真实 4xx 共用同一份文案解析，不再各写一套 errorCode 拼接。
   */
  async printErrMsg(data) {
    await reportFailure({ response: { status: 200, data } })
  }
}

