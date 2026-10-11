package com.ruoyi.framework.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;
import com.ruoyi.system.service.ISysUserService;

/**
 * 用户验证处理
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    private final ISysUserService userService;

    private final SysPasswordService passwordService;

    private final SysPermissionService permissionService;

    public UserDetailsServiceImpl(ISysUserService userService, SysPasswordService passwordService,
            SysPermissionService permissionService) {
        this.userService = userService;
        this.passwordService = passwordService;
        this.permissionService = permissionService;
    }

    /**
     * Spring Security 认证回调：按用户名校查用户，依次拦截不存在/已删除/已停用，
     * 再交由 SysPasswordService.validate 校验密码（含重试锁定），通过后装配带权限的 LoginUser
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userService.selectUserByUserName(username);
        if (StringUtils.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        } else if (UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        } else if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", username);
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }

        // 从 ThreadLocal 认证上下文取明文密码（UserDetailsService 接口签名只传 username，
        // 这是本仓唯一的桥接点，往下游 validate() 做显式传参）
        Authentication auth = AuthenticationContextHolder.getContext();
        String rawPassword = auth.getCredentials().toString();
        passwordService.validate(username, rawPassword, user);

        return createLoginUser(user);
    }

    /**
     * 将 SysUser 包装为 Spring Security 可用的 LoginUser，并携带其菜单权限集合
     */
    public UserDetails createLoginUser(SysUser user) {
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }
}
