package com.ruoyi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 回收站 Mapper 层集成测试：在真实 MySQL 上验证按主键取回收站记录、恢复、物理删除三条 SQL 的语义。
 *
 * <p>
 * 锁定三点契约：{@code selectDeletedXxxById} 仅命中 del_flag='2' 的行（对活跃行返回 null）；
 * {@code restoreXxxById} 带 del_flag='2' 前置——对回收站行返回 1 并翻回 '0'、再次调用返回 0（幂等）、
 * 对活跃行返回 0（绝不误翻）；{@code deleteXxxByIdPhysically} 真删整行（删后主键查询与原始 count 皆空）。
 * 用例自建软删/活跃 fixture，方法级事务结束后自动回滚，对库零残留。
 * </p>
 *
 * <p>
 * 不覆盖 {@code selectDeletedXxxList}：其 SQL 含 {@code ${params.dataScope}}，依赖 {@code @DataScope} 切面注入，
 * 直连 Mapper 绕开切面会有占位符渲染风险，故该列表法的可见性交给服务层单测（软删口径）覆盖。
 * </p>
 */
@DisplayName("回收站 Mapper：按id取/恢复/物理删（真实 MySQL，事务回滚）")
public class RecycleBinMapperIntegrationTest extends BaseMapperIntegrationTest {
    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysDeptMapper deptMapper;

    @Test
    @DisplayName("用户：软删口径取回、恢复幂等且不误翻活跃行、物理删真删")
    void userLifecycle() {
        String tag = "it_rb_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long trashed = insertUser(tag, "2");
        Long active = insertUser(tag + "_ctl", "0");

        SysUser loaded = userMapper.selectDeletedUserById(trashed);
        assertNotNull(loaded, "回收站应能按 id 取到 del_flag='2' 的用户");
        assertEquals(tag, loaded.getUserName());
        assertNull(userMapper.selectDeletedUserById(active), "活跃用户不得被回收站查询命中");

        assertEquals(1, userMapper.restoreUserById(trashed), "首次恢复应命中并翻回 del_flag='0'");
        assertEquals("0", userDelFlag(trashed));
        assertEquals(0, userMapper.restoreUserById(trashed), "再次恢复应因 del_flag='2' 前置而不生效（幂等）");
        assertEquals(0, userMapper.restoreUserById(active), "对活跃行恢复不得误翻");

        assertEquals(1, userMapper.deleteUserByIdPhysically(trashed), "物理删应删除回收站行");
        assertNull(userMapper.selectDeletedUserById(trashed));
        assertEquals(0, userRowCount(trashed), "物理删后按主键应查无此行");
    }

    @Test
    @DisplayName("角色：软删口径取回、恢复幂等、物理删真删")
    void roleLifecycle() {
        String tag = "it_rb_role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long trashed = insertRole(tag);

        SysRole loaded = roleMapper.selectDeletedRoleById(trashed);
        assertNotNull(loaded, "回收站应能按 id 取到 del_flag='2' 的角色");
        assertEquals(tag, loaded.getRoleName());

        assertEquals(1, roleMapper.restoreRoleById(trashed));
        assertEquals("0", roleDelFlag(trashed));
        assertEquals(0, roleMapper.restoreRoleById(trashed), "再次恢复幂等返回 0");

        assertEquals(1, roleMapper.deleteRoleByIdPhysically(trashed));
        assertEquals(0, roleRowCount(trashed), "物理删后按主键应查无此行");
    }

    @Test
    @DisplayName("部门：软删口径取回、恢复幂等、物理删真删")
    void deptLifecycle() {
        String tag = "it_rb_dept_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long trashed = insertDept(tag);

        SysDept loaded = deptMapper.selectDeletedDeptById(trashed);
        assertNotNull(loaded, "回收站应能按 id 取到 del_flag='2' 的部门");
        assertEquals(tag, loaded.getDeptName());

        assertEquals(1, deptMapper.restoreDeptById(trashed));
        assertEquals("0", deptDelFlag(trashed));
        assertEquals(0, deptMapper.restoreDeptById(trashed), "再次恢复幂等返回 0");

        assertEquals(1, deptMapper.deleteDeptByIdPhysically(trashed));
        assertEquals(0, deptRowCount(trashed), "物理删后按主键应查无此行");
    }

    // ---- fixture helpers（软删 fixture 打 del_flag='2'，活跃对照打 '0'）----

    private Long insertUser(String userName, String delFlag) {
        jdbc.update("insert into sys_user(user_name, nick_name, status, del_flag, create_by) "
                + "values(?, ?, '0', ?, 'itest')", userName, userName, delFlag);
        return jdbc.queryForObject("select user_id from sys_user where user_name = ?", Long.class, userName);
    }

    private String userDelFlag(Long userId) {
        return jdbc.queryForObject("select del_flag from sys_user where user_id = ?", String.class, userId);
    }

    private int userRowCount(Long userId) {
        return jdbc.queryForObject("select count(*) from sys_user where user_id = ?", Integer.class, userId);
    }

    private Long insertRole(String roleName) {
        jdbc.update("insert into sys_role(role_name, role_key, role_sort, status, del_flag, create_by) "
                + "values(?, ?, 99, '0', '2', 'itest')", roleName, roleName);
        return jdbc.queryForObject("select role_id from sys_role where role_name = ?", Long.class, roleName);
    }

    private String roleDelFlag(Long roleId) {
        return jdbc.queryForObject("select del_flag from sys_role where role_id = ?", String.class, roleId);
    }

    private int roleRowCount(Long roleId) {
        return jdbc.queryForObject("select count(*) from sys_role where role_id = ?", Integer.class, roleId);
    }

    private Long insertDept(String deptName) {
        jdbc.update("insert into sys_dept(parent_id, dept_name, order_num, status, del_flag, create_by) "
                + "values(0, ?, 0, '0', '2', 'itest')", deptName);
        return jdbc.queryForObject("select dept_id from sys_dept where dept_name = ?", Long.class, deptName);
    }

    private String deptDelFlag(Long deptId) {
        return jdbc.queryForObject("select del_flag from sys_dept where dept_id = ?", String.class, deptId);
    }

    private int deptRowCount(Long deptId) {
        return jdbc.queryForObject("select count(*) from sys_dept where dept_id = ?", Integer.class, deptId);
    }
}
