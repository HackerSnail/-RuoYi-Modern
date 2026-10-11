package com.ruoyi.framework.manager.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.LogUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.UserAgentUtils;
import com.ruoyi.common.utils.ip.AddressUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.domain.SysLogininfor;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.service.ISysLogininforService;
import com.ruoyi.system.service.ISysOperLogService;

/**
 * 异步工厂（产生任务用）
 * 
 * @author ruoyi
 */
public class AsyncFactory {
    private static final Logger sys_user_logger = LoggerFactory.getLogger("sys-user");

    /**
     * sys_logininfor 各列宽度（对齐 DDL：user_name(50)/ipaddr(128)/login_location(255)/
     * browser(50)/os(50)/msg(255)）。入库前据此 trim + 截断，防超长/脏输入撑爆审计表，
     * 或在 MySQL 严格模式下因超列宽报错而丢失整条登录审计。status 由服务端归一化为 0/1，不经此处理。
     */
    private static final int MAX_USER_NAME_LEN = 50;
    private static final int MAX_IPADDR_LEN = 128;
    private static final int MAX_LOGIN_LOCATION_LEN = 255;
    private static final int MAX_BROWSER_LEN = 50;
    private static final int MAX_OS_LEN = 50;
    private static final int MAX_MSG_LEN = 255;

    /**
     * 记录登录信息
     * 
     * @param username  用户名
     * @param status    状态
     * @param message   消息
     * @param ip        客户端 IP（由调用方在请求线程提前取得并传入，使异步线程不再访问 Request 上下文）
     * @param userAgent 客户端 User-Agent（由调用方在请求线程提前取得并传入）
     * @param args      列表
     * @return 任务task
     */
    public static Runnable recordLogininfor(final String username, final String status, final String message,
            final String ip, final String userAgent, final Object... args) {
        return new Runnable() {
            @Override
            public void run() {
                String address = AddressUtils.getRealAddressByIP(ip);
                StringBuilder s = new StringBuilder();
                s.append(LogUtils.getBlock(ip));
                s.append(address);
                s.append(LogUtils.getBlock(username));
                s.append(LogUtils.getBlock(status));
                s.append(LogUtils.getBlock(message));
                // 打印信息到日志
                sys_user_logger.info(s.toString(), args);
                // 获取客户端操作系统
                String os = UserAgentUtils.getOperatingSystem(userAgent);
                // 获取客户端浏览器
                String browser = UserAgentUtils.getBrowser(userAgent);
                // 封装对象（各字符串字段入库前 trim + 按列宽截断做纵深防御；上方日志上下文仍用原值不截断）
                SysLogininfor logininfor = new SysLogininfor();
                logininfor.setUserName(cap(username, MAX_USER_NAME_LEN));
                logininfor.setIpaddr(cap(ip, MAX_IPADDR_LEN));
                logininfor.setLoginLocation(cap(address, MAX_LOGIN_LOCATION_LEN));
                logininfor.setBrowser(cap(browser, MAX_BROWSER_LEN));
                logininfor.setOs(cap(os, MAX_OS_LEN));
                logininfor.setMsg(cap(message, MAX_MSG_LEN));
                // 日志状态
                if (StringUtils.equalsAny(status, Constants.LOGIN_SUCCESS, Constants.LOGOUT, Constants.REGISTER)) {
                    logininfor.setStatus(Constants.SUCCESS);
                } else if (Constants.LOGIN_FAIL.equals(status)) {
                    logininfor.setStatus(Constants.FAIL);
                }
                // 插入数据
                SpringUtils.getBean(ISysLogininforService.class).insertLogininfor(logininfor);
            }
        };
    }

    /**
     * 操作日志记录
     * 
     * @param operLog 操作日志信息
     * @return 任务task
     */
    public static Runnable recordOper(final SysOperLog operLog) {
        return new Runnable() {
            @Override
            public void run() {
                // 远程查询操作地点
                operLog.setOperLocation(AddressUtils.getRealAddressByIP(operLog.getOperIp()));
                SpringUtils.getBean(ISysOperLogService.class).insertOperlog(operLog);
            }
        };
    }

    /**
     * 纵深防御：入库前对字符串字段去首尾空白并按目标列宽截断。
     * null 原样返回（对应列可空、有 default ''）；超长仅截断显示长度，不改变语义字段。
     * 包级可见以便对本纯函数做无 Spring 单测。
     */
    static String cap(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }
}
