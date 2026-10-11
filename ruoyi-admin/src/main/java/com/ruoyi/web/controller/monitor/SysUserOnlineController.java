package com.ruoyi.web.controller.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysUserOnline;
import com.ruoyi.system.service.ISysUserOnlineService;

/**
 * 在线用户监控
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/monitor/online")
public class SysUserOnlineController extends BaseController {
    private final ISysUserOnlineService userOnlineService;

    private final TokenService tokenService;

    public SysUserOnlineController(ISysUserOnlineService userOnlineService, TokenService tokenService) {
        this.userOnlineService = userOnlineService;
        this.tokenService = tokenService;
    }

    /**
     * 获取在线用户列表
     * <p>
     * 在线状态源于 Redis 中的登录 token（非数据库），遍历全部 token 缓存并按 ip/用户名过滤。
     * 下发的 tokenId 是脱敏会话标识（非真实会话键），仅用于回传给强退接口定位会话。
     */
    @PreAuthorize("@ss.hasPermi('monitor:online:list')")
    @GetMapping("/list")
    public TableDataInfo list(String ipaddr, String userName) {
        // SCAN login_tokens:* 后一次 MGET 取回全部会话（SCAN+MGET 样板已收口到 TokenService 复用）
        List<LoginUser> userList = tokenService.listOnlineLoginUsers();
        List<SysUserOnline> userOnlineList = new ArrayList<>();
        for (LoginUser user : userList) {
            if (StringUtils.isNull(user) || StringUtils.isNull(user.getUser())) {
                // 残缺会话（反序列化不完整/内部构造）跳过：避免下面取 token 时 NPE 拖垮整表
                continue;
            }
            // 真实会话 uuid 只在此处换算成单向脱敏标识，绝不进响应体
            String sessionHandle = tokenService.toSessionHandle(user.getToken());
            if (StringUtils.isNotEmpty(ipaddr) && StringUtils.isNotEmpty(userName)) {
                userOnlineList.add(userOnlineService.selectOnlineByInfo(ipaddr, userName, sessionHandle, user));
            } else if (StringUtils.isNotEmpty(ipaddr)) {
                userOnlineList.add(userOnlineService.selectOnlineByIpaddr(ipaddr, sessionHandle, user));
            } else if (StringUtils.isNotEmpty(userName)) {
                userOnlineList.add(userOnlineService.selectOnlineByUserName(userName, sessionHandle, user));
            } else {
                userOnlineList.add(userOnlineService.loginUserToUserOnline(sessionHandle, user));
            }
        }
        // 不匹配过滤条件时 service 返回 null，统一剔除
        userOnlineList.removeAll(Collections.singleton(null));
        // 按登录时间倒序（最近登录在前）。此处不能用 Collections.reverse：数据来自 SCAN，
        // SCAN 返回顺序本身是任意的，反转得到的并不是时间序；loginTime 缺失视为最旧（排末尾）
        userOnlineList.sort(Comparator
                .comparingLong((SysUserOnline online) -> online.getLoginTime() == null ? 0L : online.getLoginTime())
                .reversed());
        return getDataTable(userOnlineList);
    }

    /**
     * 强退用户：按脱敏会话标识反查真实会话键并删除，该用户下次请求将因会话失效而被踢下线
     */
    @PreAuthorize("@ss.hasPermi('monitor:online:forceLogout')")
    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @DeleteMapping("/{tokenId}")
    public AjaxResult forceLogout(@PathVariable String tokenId) {
        // 标识格式非法由 ServiceException 收敛为 400；返回 0 表示会话已过期/已重登（标识失配），回操作失败提示
        return toAjax(tokenService.delLoginUserByHandle(tokenId));
    }
}
