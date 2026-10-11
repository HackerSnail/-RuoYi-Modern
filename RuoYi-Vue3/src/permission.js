import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import { isHttp, isPathMatch } from '@/utils/validate'
import { isRelogin } from '@/utils/request'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'
import usePermissionStore from '@/store/modules/permission'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login', '/register']

const isWhiteList = (path) => {
  return whiteList.some(pattern => isPathMatch(pattern, path))
}

router.beforeEach(async (to, from) => {
  NProgress.start()
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title)
    const isLock = useLockStore().isLock
    if (to.path === '/login') {
      NProgress.done()
      return { path: '/' }
    }
    if (isWhiteList(to.path)) {
      return true
    }
    if (isLock && to.path !== '/lock') {
      NProgress.done()
      return { path: '/lock' }
    }
    if (!isLock && to.path === '/lock') {
      NProgress.done()
      return { path: '/' }
    }
    if (useUserStore().roles.length === 0) {
      isRelogin.show = true
      try {
        // 拉取user_info信息
        await useUserStore().getInfo()
        isRelogin.show = false
        // 根据roles权限生成可访问的路由
        const accessRoutes = await usePermissionStore().generateRoutes()
        accessRoutes.forEach(route => {
          if (!isHttp(route.path)) {
            router.addRoute(route)
          }
        })
        // 重新导航到目标路由，确保动态路由已注册
        return { ...to, replace: true }
      } catch (err) {
        // 必须复位：拦下“重新登录”弹窗的就是这个 show 标志，不复位则本次会话内任何 401 都弹不出来，
        // 用户会被卡在已失效的会话里反复报错
        isRelogin.show = false
        // 本地态清理不挂在 /logout 的成功上（见 store/modules/user.js 的 logOut）：断网或服务端拒绝时
        // 旧 token 也必须丢，否则带着脏 token 重进守卫又拉一次 getInfo，形成循环
        await useUserStore().logOut()
        // 本分支是唯一的用户反馈出口（守卫把 show 置了 true，拦截器的会话过期弹窗被刻意压住）。
        // 业务失败分支回拒的是 Error 对象，直接丢给 ElMessage 会渲染成 [object Error]
        ElMessage.error(err?.message || err)
        return { path: '/' }
      }
    }
    return true
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true
    }
    NProgress.done()
    return `/login?redirect=${to.fullPath}` // 否则全部重定向到登录页
  }
})

router.afterEach(() => {
  NProgress.done()
})
