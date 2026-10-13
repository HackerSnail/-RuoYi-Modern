package com.ruoyi.common.utils;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.PatternMatchUtils;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;

/**
 * 安全服务工具类
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public final class SecurityUtils {
    /**
     * 密码编码器：BCryptPasswordEncoder 无状态、线程安全，提为常量避免每次 new
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private SecurityUtils() {
    }

    /**
     * 用户ID
     **/
    public static Long getUserId() {
        return getLoginUser().getUserId();
    }

    /**
     * 获取部门ID
     **/
    public static Long getDeptId() {
        return getLoginUser().getDeptId();
    }

    /**
     * 获取用户账户
     **/
    public static String getUsername() {
        return getLoginUser().getUsername();
    }

    /**
     * 获取用户
     **/
    public static LoginUser getLoginUser() {
        Authentication authentication = getAuthentication();
        // 定时任务/异步线程/匿名接口/Token 失效时 getAuthentication() 可能为 null，显式判空给出准确文案，
        // 不再依赖捕获 NPE 做控制流。
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ServiceException("用户未登录", HttpStatus.UNAUTHORIZED);
        }
        try {
            return (LoginUser) authentication.getPrincipal();
        } catch (ClassCastException e) {
            throw new ServiceException("用户信息类型异常", HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * 获取Authentication
     */
    public static Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * 生成BCryptPasswordEncoder密码
     *
     * @param password 密码
     * @return 加密字符串
     */
    public static String encryptPassword(String password) {
        return PASSWORD_ENCODER.encode(password);
    }

    /**
     * 判断密码是否相同
     *
     * @param rawPassword     真实密码
     * @param encodedPassword 加密后字符
     * @return 结果
     */
    public static boolean matchesPassword(String rawPassword, String encodedPassword) {
        return PASSWORD_ENCODER.matches(rawPassword, encodedPassword);
    }

    /**
     * 是否为管理员
     * 
     * @return 结果
     */
    public static boolean isAdmin() {
        return isAdmin(getUserId());
    }

    /**
     * 是否为管理员
     * 
     * @param userId 用户ID
     * @return 结果
     */
    public static boolean isAdmin(Long userId) {
        return userId != null && 1L == userId;
    }

    /**
     * 验证用户是否具备某权限
     * 
     * @param permission 权限字符串
     * @return 用户是否具备某权限
     */
    public static boolean hasPermi(String permission) {
        return hasPermi(getLoginUser().getPermissions(), permission);
    }

    /**
     * 判断是否包含权限
     * 
     * @param authorities 权限列表
     * @param permission  权限字符串
     * @return 用户是否具备某权限
     */
    public static boolean hasPermi(Collection<String> authorities, String permission) {
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        return authorities.stream().filter(StringUtils::hasText)
                .anyMatch(x -> Constants.ALL_PERMISSION.equals(x) || PatternMatchUtils.simpleMatch(x, permission));
    }

    /**
     * 验证用户是否拥有某个角色
     * 
     * @param role 角色标识
     * @return 用户是否具备某角色
     */
    public static boolean hasRole(String role) {
        LoginUser loginUser = getLoginUser();
        // getUser() 在异常 LoginUser 下可能为 null，getRoles() 也可能为 null，逐层兜底。
        if (loginUser.getUser() == null || loginUser.getUser().getRoles() == null) {
            return false;
        }
        List<SysRole> roleList = loginUser.getUser().getRoles();
        Collection<String> roles = roleList.stream().map(sysRole -> sysRole.getRoleKey()).collect(Collectors.toSet());
        return hasRole(roles, role);
    }

    /**
     * 判断是否包含角色
     * 
     * @param roles 角色列表
     * @param role  角色
     * @return 用户是否具备某角色权限
     */
    public static boolean hasRole(Collection<String> roles, String role) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream().filter(StringUtils::hasText)
                .anyMatch(x -> Constants.SUPER_ADMIN.equals(x) || PatternMatchUtils.simpleMatch(x, role));
    }

}
