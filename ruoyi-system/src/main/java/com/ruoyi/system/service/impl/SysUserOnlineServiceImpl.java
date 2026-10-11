package com.ruoyi.system.service.impl;

import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysUserOnline;
import com.ruoyi.system.service.ISysUserOnlineService;

/**
 * 在线用户 服务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysUserOnlineServiceImpl implements ISysUserOnlineService {
    /**
     * 通过登录地址查询信息
     * 
     * @param ipaddr 登录地址
     * @param sessionHandle 脱敏会话标识
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByIpaddr(String ipaddr, String sessionHandle, LoginUser user) {
        if (isBlankCondition(ipaddr) || !isShowable(user)) {
            return null;
        }
        if (StringUtils.equals(ipaddr, user.getIpaddr())) {
            return loginUserToUserOnline(sessionHandle, user);
        }
        return null;
    }

    /**
     * 通过用户名称查询信息
     * 
     * @param userName 用户名称
     * @param sessionHandle 脱敏会话标识
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByUserName(String userName, String sessionHandle, LoginUser user) {
        if (isBlankCondition(userName) || !isShowable(user)) {
            return null;
        }
        if (StringUtils.equals(userName, user.getUsername())) {
            return loginUserToUserOnline(sessionHandle, user);
        }
        return null;
    }

    /**
     * 通过登录地址/用户名称查询信息
     * 
     * @param ipaddr 登录地址
     * @param userName 用户名称
     * @param sessionHandle 脱敏会话标识
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByInfo(String ipaddr, String userName, String sessionHandle, LoginUser user) {
        // AND 语义下任一条件为空即视为条件不足，不降级成单条件匹配
        if (isBlankCondition(ipaddr) || isBlankCondition(userName) || !isShowable(user)) {
            return null;
        }
        if (StringUtils.equals(ipaddr, user.getIpaddr()) && StringUtils.equals(userName, user.getUsername())) {
            return loginUserToUserOnline(sessionHandle, user);
        }
        return null;
    }

    /**
     * 设置在线用户信息
     * 
     * @param sessionHandle 脱敏会话标识（真实会话键到不了这一层）
     * @param user 用户信息
     * @return 在线用户
     */
    @Override
    public SysUserOnline loginUserToUserOnline(String sessionHandle, LoginUser user) {
        if (!isShowable(user)) {
            return null;
        }
        SysUserOnline sysUserOnline = new SysUserOnline();
        // tokenId 只承载脱敏 handle：本方法不读 user.getToken()，真实会话键不外泄
        sysUserOnline.setTokenId(sessionHandle);
        sysUserOnline.setUserName(user.getUsername());
        sysUserOnline.setIpaddr(user.getIpaddr());
        sysUserOnline.setLoginLocation(user.getLoginLocation());
        sysUserOnline.setBrowser(user.getBrowser());
        sysUserOnline.setOs(user.getOs());
        sysUserOnline.setLoginTime(user.getLoginTime());
        if (StringUtils.isNotNull(user.getUser().getDept())) {
            sysUserOnline.setDeptName(user.getUser().getDept().getDeptName());
        }
        return sysUserOnline;
    }

    /**
     * 会话是否可展示：LoginUser 或其内层 SysUser 缺失都视为残缺。
     * <p>
     * 判空必须前置于任何取值：{@code LoginUser.getUsername()} 实现是
     * {@code user.getUserName()}，SysUser 为 null 时直接 NPE——而本类方法都在
     * “遍历全部在线会话逐个匹配”的循环里被调用，一条残缺会话就会拖垮整个在线列表。
     */
    private static boolean isShowable(LoginUser user) {
        return StringUtils.isNotNull(user) && StringUtils.isNotNull(user.getUser());
    }

    /**
     * 查询条件是否为空白（本仓 StringUtils.isEmpty(String) 已把纯空格视为空）。
     * <p>
     * {@code StringUtils.equals(null, null)} 返回 true，若放任空条件进来，会把对应字段
     * 恰好也为空的会话全部误命中（“全命中”陷阱），故空条件一律不匹配。
     */
    private static boolean isBlankCondition(String condition) {
        return StringUtils.isEmpty(condition);
    }
}
