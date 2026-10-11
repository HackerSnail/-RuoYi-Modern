package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.SysUserOnline;

/**
 * {@link SysUserOnlineServiceImpl} 单元测试：覆盖本轮三条主线——
 * 判空前置（残缺会话不再 NPE）、空白查询条件不命中（堵 equals(null,null) 全命中）、
 * tokenId 只承载传入的脱敏 handle（真实会话键不外泄）。
 * 纯 POJO 测试，无需 Spring/Redis。
 *
 * @author ruoyi
 */
class SysUserOnlineServiceImplTest {

    /** 模拟真实会话键：绝不允许出现在返回对象里 */
    private static final String REAL_UUID = "2f1c8a9e-7b3d-4e5f-9a0b-1c2d3e4f5a6b";

    /** 模拟脱敏后的展示标识 */
    private static final String HANDLE = "0123456789abcdef0123456789abcdef";

    private SysUserOnlineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysUserOnlineServiceImpl();
    }

    private LoginUser loginUser(String ipaddr, SysUser sysUser) {
        LoginUser user = new LoginUser(sysUser, Set.of());
        // 真实会话键照常挂在 LoginUser 上，用于验证它不会被搬到 VO 里
        user.setToken(REAL_UUID);
        user.setIpaddr(ipaddr);
        user.setLoginLocation("北京");
        user.setBrowser("Chrome");
        user.setOs("Windows");
        user.setLoginTime(1_700_000_000_000L);
        return user;
    }

    private SysUser sysUser(String userName, String deptName) {
        SysUser sysUser = new SysUser();
        sysUser.setUserName(userName);
        if (deptName != null) {
            SysDept dept = new SysDept();
            dept.setDeptName(deptName);
            sysUser.setDept(dept);
        }
        return sysUser;
    }

    @Test
    @DisplayName("selectOnlineByIpaddr：LoginUser 为 null 时返回 null，不抛 NPE")
    void ipaddrQueryRejectsNullLoginUser() {
        assertNull(service.selectOnlineByIpaddr("127.0.0.1", HANDLE, null));
    }

    @Test
    @DisplayName("selectOnlineByUserName：内层 SysUser 缺失时返回 null（原实现必 NPE）")
    void userNameQueryRejectsLoginUserWithoutSysUser() {
        // LoginUser.getUsername() 的实现是 user.getUserName()，SysUser 为 null 即 NPE
        LoginUser userWithoutSysUser = loginUser("127.0.0.1", null);

        assertDoesNotThrow(() -> service.selectOnlineByUserName("admin", HANDLE, userWithoutSysUser));
        assertNull(service.selectOnlineByUserName("admin", HANDLE, userWithoutSysUser));
    }

    @Test
    @DisplayName("selectOnlineByInfo：内层 SysUser 缺失时返回 null（Controller 原先只防了单个分支）")
    void infoQueryRejectsLoginUserWithoutSysUser() {
        LoginUser userWithoutSysUser = loginUser("127.0.0.1", null);

        assertNull(service.selectOnlineByInfo("127.0.0.1", "admin", HANDLE, userWithoutSysUser));
    }

    @Test
    @DisplayName("空白条件不命中任何会话：堵 StringUtils.equals(null, null)=true 的全命中")
    void blankConditionMatchesNothing() {
        // 会话 ipaddr 恰为 null：若条件也传 null，旧逻辑会把这条会话误判命中
        LoginUser nullIpUser = loginUser(null, sysUser("admin", null));

        assertNull(service.selectOnlineByIpaddr(null, HANDLE, nullIpUser));
        assertNull(service.selectOnlineByIpaddr("", HANDLE, nullIpUser));
        assertNull(service.selectOnlineByIpaddr("   ", HANDLE, nullIpUser));
        assertNull(service.selectOnlineByUserName(null, HANDLE, nullIpUser));
        assertNull(service.selectOnlineByInfo(null, "admin", HANDLE, nullIpUser));
        assertNull(service.selectOnlineByInfo("127.0.0.1", "", HANDLE, nullIpUser));
    }

    @Test
    @DisplayName("条件匹配则组装完整 VO，且 tokenId 就是传入的脱敏 handle")
    void matchingConditionBuildsVoWithOpaqueHandle() {
        LoginUser user = loginUser("127.0.0.1", sysUser("admin", "研发部"));

        SysUserOnline byIp = service.selectOnlineByIpaddr("127.0.0.1", HANDLE, user);
        SysUserOnline byName = service.selectOnlineByUserName("admin", HANDLE, user);
        SysUserOnline byInfo = service.selectOnlineByInfo("127.0.0.1", "admin", HANDLE, user);

        assertNotNull(byIp);
        assertEquals(HANDLE, byIp.getTokenId());
        assertEquals("admin", byIp.getUserName());
        assertEquals("127.0.0.1", byIp.getIpaddr());
        assertEquals("北京", byIp.getLoginLocation());
        assertEquals("Chrome", byIp.getBrowser());
        assertEquals("Windows", byIp.getOs());
        assertEquals(Long.valueOf(1_700_000_000_000L), byIp.getLoginTime());
        assertEquals("研发部", byIp.getDeptName());
        assertEquals(HANDLE, byName.getTokenId());
        assertEquals(HANDLE, byInfo.getTokenId());
    }

    @Test
    @DisplayName("条件不匹配返回 null")
    void unmatchedConditionReturnsNull() {
        LoginUser user = loginUser("127.0.0.1", sysUser("admin", "研发部"));

        assertNull(service.selectOnlineByIpaddr("10.0.0.9", HANDLE, user));
        assertNull(service.selectOnlineByUserName("ruoyi", HANDLE, user));
        assertNull(service.selectOnlineByInfo("10.0.0.9", "admin", HANDLE, user));
    }

    @Test
    @DisplayName("loginUserToUserOnline：真实会话键不出现在返回对象里")
    void conversionNeverLeaksRealSessionUuid() {
        LoginUser user = loginUser("127.0.0.1", sysUser("admin", "研发部"));

        SysUserOnline online = service.loginUserToUserOnline(HANDLE, user);

        assertNotNull(online);
        assertEquals(HANDLE, online.getTokenId());
        assertFalse(online.getTokenId().contains(REAL_UUID), "tokenId 不得携带真实会话 uuid");
    }

    @Test
    @DisplayName("loginUserToUserOnline：dept 缺失时不设置部门名（保持既有行为）")
    void conversionToleratesMissingDept() {
        LoginUser user = loginUser("127.0.0.1", sysUser("admin", null));

        SysUserOnline online = service.loginUserToUserOnline(HANDLE, user);

        assertNotNull(online);
        assertNull(online.getDeptName());
    }

    @Test
    @DisplayName("loginUserToUserOnline：SysUser 缺失或对象为 null 时返回 null")
    void conversionRejectsIncompleteLoginUser() {
        assertNull(service.loginUserToUserOnline(HANDLE, loginUser("127.0.0.1", null)));
        assertNull(service.loginUserToUserOnline(HANDLE, null));
    }
}
