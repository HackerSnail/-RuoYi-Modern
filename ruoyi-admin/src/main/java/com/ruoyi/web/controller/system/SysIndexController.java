package com.ruoyi.web.controller.system;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.SysPasswordService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 首页
 *
 * @author ruoyi
 */
@RestController
public class SysIndexController {
    /** 系统基础配置 */
    private final RuoYiConfig ruoyiConfig;

    private final ISysUserService userService;

    private final SysPasswordService passwordService;

    public SysIndexController(RuoYiConfig ruoyiConfig, ISysUserService userService,
            SysPasswordService passwordService) {
        this.ruoyiConfig = ruoyiConfig;
        this.userService = userService;
        this.passwordService = passwordService;
    }

    /**
     * 访问首页，提示语
     */
    @RequestMapping("/")
    public String index() {
        return StringUtils.format("欢迎使用{}后台管理框架，当前版本：v{}，请通过前端地址访问。", ruoyiConfig.getName(), ruoyiConfig.getVersion());
    }

    /**
     * 解锁屏幕：息屏锁定后，重新校验当前登录用户密码以解锁（不重新登录）
     * <p>
     * 安全加固：
     * ① 防暴力破解——密码比对复用 {@link SysPasswordService#validateUnlock} 的失败计数与锁定阈值，
     * 连续错误达 maxRetryCount 次即锁定 lockTime 分钟，杜绝持有效 Token 者无限尝试；
     * ② 审计留痕——@Log 使每次解锁（成功/失败）都写入 sys_oper_log，便于回溯接口是否被滥用；
     * 因请求体含明文密码，特意关闭 isSaveRequestData，只留操作人/时间/URL/结果，不把密码写进日志；
     * 失败统一抛 ServiceException，由全局异常处理器原样返回提示（响应体与原 AjaxResult.error 一致）。
     * 密码传输保持与登录一致（HTTPS 明文，本系统未启用 RSA），若未来登录改用加密需同步此处。
     */
    @Log(title = "屏幕解锁", businessType = BusinessType.OTHER, isSaveRequestData = false)
    @PostMapping("/unlockscreen")
    public AjaxResult unlockScreen(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (StringUtils.isEmpty(password)) {
            throw new ServiceException("密码不能为空");
        }
        String username = SecurityUtils.getUsername();
        SysUser user = userService.selectUserByUserName(username);
        if (user == null) {
            // 用户大概率已被删除/禁用；对外统一提示会话失效，不暴露"用户不存在"
            throw new ServiceException("会话已失效，请重新登录");
        }
        // 复用登录的密码错误次数锁定策略，防解锁接口被暴力破解
        passwordService.validateUnlock(username, password, user);
        return AjaxResult.success("解锁成功");
    }
}
