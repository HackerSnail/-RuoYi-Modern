package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.SysUserOnline;

/**
 * 在线用户 服务层
 * <p>
 * 本层只做「会话对象 → 展示 VO」的转换与过滤，<b>不接触真实会话键</b>：下发给前端的
 * {@code tokenId} 一律由调用方（监控页）传入的脱敏 handle 填充，本层从不读
 * {@code LoginUser.getToken()}。
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public interface ISysUserOnlineService {
    /**
     * 通过登录地址查询信息
     * 
     * @param ipaddr 登录地址（为空则不命中任何会话）
     * @param sessionHandle 脱敏会话标识（由调用方换算后传入，非真实会话键）
     * @param user 用户信息
     * @return 在线用户信息，不匹配或会话残缺时返回 null
     */
    public SysUserOnline selectOnlineByIpaddr(String ipaddr, String sessionHandle, LoginUser user);

    /**
     * 通过用户名称查询信息
     * 
     * @param userName 用户名称（为空则不命中任何会话）
     * @param sessionHandle 脱敏会话标识（由调用方换算后传入，非真实会话键）
     * @param user 用户信息
     * @return 在线用户信息，不匹配或会话残缺时返回 null
     */
    public SysUserOnline selectOnlineByUserName(String userName, String sessionHandle, LoginUser user);

    /**
     * 通过登录地址/用户名称查询信息（两条件须同时非空且同时匹配）
     * 
     * @param ipaddr 登录地址
     * @param userName 用户名称
     * @param sessionHandle 脱敏会话标识（由调用方换算后传入，非真实会话键）
     * @param user 用户信息
     * @return 在线用户信息，不匹配或会话残缺时返回 null
     */
    public SysUserOnline selectOnlineByInfo(String ipaddr, String userName, String sessionHandle, LoginUser user);

    /**
     * 设置在线用户信息
     * 
     * @param sessionHandle 脱敏会话标识（由调用方换算后传入，非真实会话键）
     * @param user 用户信息
     * @return 在线用户，用户信息残缺（无 SysUser）时返回 null
     */
    public SysUserOnline loginUserToUserOnline(String sessionHandle, LoginUser user);
}
