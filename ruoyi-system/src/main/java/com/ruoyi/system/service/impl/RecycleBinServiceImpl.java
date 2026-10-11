package com.ruoyi.system.service.impl;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.RecycleBinType;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.domain.dto.RecycleBinOpResult;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.IRecycleBinService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 回收站 业务层处理
 * <p>
 * 数据权限采"软删口径"方案：回收站作用对象是 del_flag='2' 记录，既有 check*DataScope（底层查活跃记录）
 * 对其不可用，故改用同样带 @DataScope 的 selectDeletedXxxList 按目标 id 过滤查一次判可见性——
 * 超管经切面短路（不加范围条件）必命中，非超管越界则查空即拒。恢复/彻底删除逐项独立处理，
 * 每项仅一条状态变更语句（单语句天然原子），批内一项失败不回滚其余，故方法本身不开事务。
 *
 * @author ruoyi
 */
@Service
public class RecycleBinServiceImpl implements IRecycleBinService {
    private static final Logger log = LoggerFactory.getLogger(RecycleBinServiceImpl.class);

    private final SysUserMapper userMapper;

    private final SysRoleMapper roleMapper;

    private final SysDeptMapper deptMapper;

    private final ISysUserService userService;

    private final ISysRoleService roleService;

    public RecycleBinServiceImpl(SysUserMapper userMapper, SysRoleMapper roleMapper, SysDeptMapper deptMapper,
            ISysUserService userService, ISysRoleService roleService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.deptMapper = deptMapper;
        this.userService = userService;
        this.roleService = roleService;
    }

    @Override
    @DataScope(deptAlias = "d", userAlias = "u")
    public List<SysUser> selectDeletedUserList(SysUser user) {
        return userMapper.selectDeletedUserList(user);
    }

    @Override
    @DataScope(deptAlias = "d")
    public List<SysRole> selectDeletedRoleList(SysRole role) {
        return roleMapper.selectDeletedRoleList(role);
    }

    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeletedDeptList(SysDept dept) {
        return deptMapper.selectDeletedDeptList(dept);
    }

    /**
     * 批量恢复：逐项独立处理、部分成功。方法本身不开事务，每项仅一条 UPDATE 单语句原子生效，
     * 一项抛异常仅记为该项失败，不影响批内其余项。
     */
    @Override
    public RecycleBinOpResult restore(RecycleBinType type, Long[] ids) {
        RecycleBinOpResult result = new RecycleBinOpResult();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            try {
                result.addSuccess(id, restoreOne(type, id));
            } catch (ServiceException e) {
                // 业务护栏文案（越权拒绝/身份冲突/悬空引用等）本就面向操作者，可安全回传
                result.addFailure(id, null, e.getMessage());
            } catch (Exception e) {
                // 非预期异常（DataAccessException/NPE 等）原始 message 可能含表名/SQL/堆栈语义，
                // 对外仅给通用文案，真实原因落日志留痕
                log.error("回收站恢复失败 type={} id={}", type, id, e);
                result.addFailure(id, null, "操作失败，请联系管理员");
            }
        }
        return result;
    }

    /**
     * 批量彻底删除：先做整请求级二次确认，再逐项独立处理。物理删不可逆，任一项通过护栏后执行单条 DELETE。
     */
    @Override
    public RecycleBinOpResult purge(RecycleBinType type, Long[] ids, boolean confirm) {
        if (!confirm) {
            throw new ServiceException("彻底删除必须二次确认（confirm=true）");
        }
        RecycleBinOpResult result = new RecycleBinOpResult();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            try {
                result.addSuccess(id, purgeOne(type, id));
            } catch (ServiceException e) {
                // 业务护栏文案（越权拒绝/自删锁死/不可销毁等）本就面向操作者，可安全回传
                result.addFailure(id, null, e.getMessage());
            } catch (Exception e) {
                // 非预期异常（DataAccessException/NPE 等）原始 message 可能含表名/SQL/堆栈语义，
                // 对外仅给通用文案，真实原因落日志留痕
                log.error("回收站彻底删除失败 type={} id={}", type, id, e);
                result.addFailure(id, null, "操作失败，请联系管理员");
            }
        }
        return result;
    }

    /** 恢复单项分派：返回恢复对象展示名，校验不过抛 ServiceException 由上层记为该项失败 */
    private String restoreOne(RecycleBinType type, Long id) {
        switch (type) {
            case USER:
                return restoreUser(id);
            case ROLE:
                return restoreRole(id);
            case DEPT:
                return restoreDept(id);
            default:
                throw new ServiceException("不支持的回收站类型：" + type);
        }
    }

    /** 彻底删除单项分派 */
    private String purgeOne(RecycleBinType type, Long id) {
        switch (type) {
            case USER:
                return purgeUser(id);
            case ROLE:
                return purgeRole(id);
            case DEPT:
                return purgeDept(id);
            default:
                throw new ServiceException("不支持的回收站类型：" + type);
        }
    }

    // ===== 用户 =====

    private String restoreUser(Long userId) {
        SysUser trash = userMapper.selectDeletedUserById(userId);
        if (trash == null) {
            throw new ServiceException("用户不在回收站");
        }
        assertUserInScope(userId);
        // 超级管理员不允许被恢复（正常也不会被删，防御性拦截）
        userService.checkUserAllowed(new SysUser(userId));
        // 身份冲突：与现存活跃记录撞登录名/手机号/邮箱
        if (userMapper.checkUserNameUnique(trash.getUserName()) != null) {
            throw new ServiceException("存在同名活跃用户：" + trash.getUserName());
        }
        if (StringUtils.isNotEmpty(trash.getPhonenumber())
                && userMapper.checkPhoneUnique(trash.getPhonenumber()) != null) {
            throw new ServiceException("手机号已被活跃用户占用");
        }
        if (StringUtils.isNotEmpty(trash.getEmail()) && userMapper.checkEmailUnique(trash.getEmail()) != null) {
            throw new ServiceException("邮箱已被活跃用户占用");
        }
        // 悬空引用：所属部门必须存在且未删除，否则恢复即造无归属数据
        if (trash.getDeptId() != null && trash.getDeptId() != 0L) {
            SysDept dept = deptMapper.selectDeptById(trash.getDeptId());
            if (dept == null) {
                throw new ServiceException("所属部门不存在，无法恢复");
            }
            if (UserStatus.DELETED.getCode().equals(dept.getDelFlag())) {
                throw new ServiceException("请先恢复所属部门");
            }
        }
        if (userMapper.restoreUserById(userId) == 0) {
            throw new ServiceException("恢复失败：记录状态已变更");
        }
        return trash.getUserName();
    }

    private String purgeUser(Long userId) {
        SysUser trash = userMapper.selectDeletedUserById(userId);
        if (trash == null) {
            throw new ServiceException("用户不在回收站");
        }
        assertUserInScope(userId);
        userService.checkUserAllowed(new SysUser(userId));
        // 自删锁死：禁止销毁当前登录用户自身，防自我锁死且不可恢复
        if (userId.equals(SecurityUtils.getUserId())) {
            throw new ServiceException("不能彻底删除当前登录用户");
        }
        if (userMapper.deleteUserByIdPhysically(userId) == 0) {
            throw new ServiceException("删除失败：记录不存在");
        }
        return trash.getUserName();
    }

    // ===== 角色 =====

    private String restoreRole(Long roleId) {
        SysRole trash = roleMapper.selectDeletedRoleById(roleId);
        if (trash == null) {
            throw new ServiceException("角色不在回收站");
        }
        assertRoleInScope(roleId);
        roleService.checkRoleAllowed(trash);
        if (roleMapper.checkRoleKeyUnique(trash.getRoleKey()) != null) {
            throw new ServiceException("存在相同标识的活跃角色：" + trash.getRoleKey());
        }
        if (roleMapper.checkRoleNameUnique(trash.getRoleName()) != null) {
            throw new ServiceException("存在同名活跃角色：" + trash.getRoleName());
        }
        if (roleMapper.restoreRoleById(roleId) == 0) {
            throw new ServiceException("恢复失败：记录状态已变更");
        }
        return trash.getRoleName();
    }

    private String purgeRole(Long roleId) {
        SysRole trash = roleMapper.selectDeletedRoleById(roleId);
        if (trash == null) {
            throw new ServiceException("角色不在回收站");
        }
        assertRoleInScope(roleId);
        roleService.checkRoleAllowed(trash);
        if (roleMapper.deleteRoleByIdPhysically(roleId) == 0) {
            throw new ServiceException("删除失败：记录不存在");
        }
        return trash.getRoleName();
    }

    // ===== 部门 =====

    private String restoreDept(Long deptId) {
        SysDept trash = deptMapper.selectDeletedDeptById(deptId);
        if (trash == null) {
            throw new ServiceException("部门不在回收站");
        }
        assertDeptInScope(deptId);
        if (deptMapper.checkDeptNameUnique(trash.getDeptName(), trash.getParentId()) != null) {
            throw new ServiceException("同一上级下存在同名活跃部门：" + trash.getDeptName());
        }
        // 悬空引用：父部门（非顶级）必须存在且未删除，须自上而下恢复
        Long parentId = trash.getParentId();
        if (parentId != null && parentId != 0L) {
            SysDept parent = deptMapper.selectDeptById(parentId);
            if (parent == null) {
                throw new ServiceException("上级部门不存在，无法恢复");
            }
            if (UserStatus.DELETED.getCode().equals(parent.getDelFlag())) {
                throw new ServiceException("请先恢复上级部门");
            }
        }
        if (deptMapper.restoreDeptById(deptId) == 0) {
            throw new ServiceException("恢复失败：记录状态已变更");
        }
        return trash.getDeptName();
    }

    private String purgeDept(Long deptId) {
        SysDept trash = deptMapper.selectDeletedDeptById(deptId);
        if (trash == null) {
            throw new ServiceException("部门不在回收站");
        }
        assertDeptInScope(deptId);
        // 阻断造孤儿的物理删：仍有活跃下级/在职用户，或回收站中的下级部门/用户挂在其下则拒绝
        // （后者若不拦，父被物理销毁后，软删子级将来恢复会因"上级部门不存在"、软删用户会因"所属部门不存在"被永久拒绝，成孤儿堆）
        if (deptMapper.hasChildByDeptId(deptId) > 0) {
            throw new ServiceException("存在活跃下级部门，不可销毁");
        }
        if (deptMapper.hasRecycledChildByDeptId(deptId) > 0) {
            throw new ServiceException("回收站中存在下级部门，不可销毁（请先恢复或销毁下级）");
        }
        if (deptMapper.checkDeptExistUser(deptId) > 0) {
            throw new ServiceException("部门下存在在职用户，不可销毁");
        }
        if (deptMapper.countRecycledUserByDeptId(deptId) > 0) {
            throw new ServiceException("回收站中存在该部门下的用户，不可销毁（请先恢复或销毁相关用户）");
        }
        if (deptMapper.deleteDeptByIdPhysically(deptId) == 0) {
            throw new ServiceException("删除失败：记录不存在");
        }
        return trash.getDeptName();
    }

    // ===== 数据权限（软删口径） =====

    /** 目标用户是否在当前用户可见的回收站范围内：经带 @DataScope 的列表按 id 过滤判可见性 */
    private void assertUserInScope(Long userId) {
        SysUser query = new SysUser();
        query.setUserId(userId);
        if (SpringUtils.getAopProxy(this).selectDeletedUserList(query).isEmpty()) {
            throw new ServiceException("没有权限访问用户数据（超出数据范围）");
        }
    }

    private void assertRoleInScope(Long roleId) {
        SysRole query = new SysRole();
        query.setRoleId(roleId);
        if (SpringUtils.getAopProxy(this).selectDeletedRoleList(query).isEmpty()) {
            throw new ServiceException("没有权限访问角色数据（超出数据范围）");
        }
    }

    private void assertDeptInScope(Long deptId) {
        SysDept query = new SysDept();
        query.setDeptId(deptId);
        if (SpringUtils.getAopProxy(this).selectDeletedDeptList(query).isEmpty()) {
            throw new ServiceException("没有权限访问部门数据（超出数据范围）");
        }
    }
}
