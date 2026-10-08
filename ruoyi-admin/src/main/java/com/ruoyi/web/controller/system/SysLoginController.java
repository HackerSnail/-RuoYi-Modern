package com.ruoyi.web.controller.system;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginBody;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 登录验证
 * 
 * @author ruoyi
 */
@RestController
public class SysLoginController {
    private final SysLoginService loginService;

    private final ISysMenuService menuService;

    private final SysPermissionService permissionService;

    private final TokenService tokenService;

    private final ISysConfigService configService;

    private final ISysUserService userService;

    public SysLoginController(SysLoginService loginService, ISysMenuService menuService,
            SysPermissionService permissionService, TokenService tokenService, ISysConfigService configService,
            ISysUserService userService) {
        this.loginService = loginService;
        this.menuService = menuService;
        this.permissionService = permissionService;
        this.tokenService = tokenService;
        this.configService = configService;
        this.userService = userService;
    }

    /**
     * 登录方法
     * 
     * @param loginBody 登录信息
     * @return 结果
     */
    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginBody loginBody) {
        AjaxResult ajax = AjaxResult.success();
        // 生成令牌
        String token = loginService.login(loginBody.getUsername(), loginBody.getPassword(), loginBody.getCode(),
                loginBody.getUuid());
        ajax.put(Constants.TOKEN, token);
        // 密码状态契约：登录成功后一并回传是否需要强制改密，前端在保存 token 后读取 forceChangePassword 决定是否强制跳转改密页。
        // 仍返回 code=200（不占用顶层状态码，避免与前端 axios 拦截器的成功判定冲突）；服务端硬拦截需另在过滤器层实现，不在本次范围。
        SysUser user = userService.selectUserByUserName(loginBody.getUsername());
        if (user != null) {
            applyPwdStatus(ajax, user);
        }
        return ajax;
    }

    /**
     * 获取用户信息
     * 
     * @return 用户信息
     */
    @GetMapping("getInfo")
    public AjaxResult getInfo() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = loginUser.getUser();
        // 角色集合
        Set<String> roles = permissionService.getRolePermission(user);
        // 权限集合
        Set<String> permissions = permissionService.getMenuPermission(user);
        // 登录态缓存的权限可能与库不一致（如刚改过角色），发现差异则刷新 token 中的权限，避免使用陈旧授权
        if (!loginUser.getPermissions().equals(permissions)) {
            loginUser.setPermissions(permissions);
            tokenService.refreshToken(loginUser);
        }
        AjaxResult ajax = AjaxResult.success();
        ajax.put("user", user);
        ajax.put("roles", roles);
        ajax.put("permissions", permissions);
        ajax.put("pwdChrtype", getSysAccountChrtype());
        applyPwdStatus(ajax, user);
        return ajax;
    }

    /**
     * 获取路由信息
     * 
     * @return 路由信息
     */
    @GetMapping("getRouters")
    public AjaxResult getRouters() {
        Long userId = SecurityUtils.getUserId();
        List<SysMenu> menus = menuService.selectMenuTreeByUserId(userId);
        return AjaxResult.success(menuService.buildMenus(menus));
    }

    /**
     * 统一写入“密码状态”契约字段，供 /login 与 /getInfo 复用：
     * <p>
     * isDefaultModifyPwd：初始/默认密码尚未修改（该配置项语义为“提醒”，不做强制，避免把“提醒”悄悄升级为“拦截”）。
     * <p>
     * isPasswordExpired：密码超过更新周期，或已启用周期但从未改过密码（该配置项语义为“必须更新”）。
     * <p>
     * forceChangePassword：需要强制跳转改密页的总开关，当前仅在“已过期”时为 true；
     * 因 passwordValidateDays 默认 0（不启用），默认恒为 false，不改变既有登录成功语义，只有管理员调大周期才会触发强制。
     */
    private void applyPwdStatus(AjaxResult ajax, SysUser user) {
        boolean needModify = initPasswordIsModify(user.getPwdUpdateDate());
        boolean expired = passwordIsExpiration(user.getPwdUpdateDate());
        ajax.put("isDefaultModifyPwd", needModify);
        ajax.put("isPasswordExpired", expired);
        ajax.put("forceChangePassword", expired);
    }

    // 获取用户密码自定义配置规则
    public String getSysAccountChrtype() {
        return Convert.toStr(configService.selectConfigByKey("sys.account.chrtype"), "0");
    }

    // 检查初始密码是否提醒修改
    public boolean initPasswordIsModify(LocalDateTime pwdUpdateDate) {
        Integer initPasswordModify = Convert.toInt(configService.selectConfigByKey("sys.account.initPasswordModify"));
        return initPasswordModify != null && initPasswordModify == 1 && pwdUpdateDate == null;
    }

    // 检查密码是否过期：基于 sys.account.passwordValidateDays 配置，从未改过密码或距上次修改超过阈值天数则视为过期
    public boolean passwordIsExpiration(LocalDateTime pwdUpdateDate) {
        Integer passwordValidateDays = Convert
                .toInt(configService.selectConfigByKey("sys.account.passwordValidateDays"));
        // 未配置或配置为 0（负数）表示不启用过期策略
        if (passwordValidateDays != null && passwordValidateDays > 0) {
            if (StringUtils.isNull(pwdUpdateDate)) {
                // 如果从未修改过初始密码，直接提醒过期
                return true;
            }
            // 距上次改密的天数超过配置阈值即判定过期
            return DateUtils.differentDaysByMillisecond(DateUtils.getNowDate(),
                    DateUtils.toDate(pwdUpdateDate)) > passwordValidateDays;
        }
        return false;
    }
}
