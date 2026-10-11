import request from '@/utils/request'

// 查询在线用户列表：数据源是服务端的登录会话（非数据库），一次返回过滤后的全量，分页由页面在前端切片完成
export function list(query) {
  return request({
    url: '/monitor/online/list',
    method: 'get',
    params: query
  })
}

// 强退用户：tokenId 承载的是服务端由会话 uuid 单向派生的脱敏会话标识（不是登录凭证），
// 服务端据此反查真实会话并踢下线；会话已过期或已重新登录时标识失配，后端返回操作失败
export function forceLogout(tokenId) {
  return request({
    url: '/monitor/online/' + tokenId,
    method: 'delete'
  })
}
