package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysRole;

/**
 * 角色表 数据层
 * 
 * @author ruoyi
 */
public interface SysRoleMapper {
    /**
     * 根据条件分页查询角色数据
     * 
     * @param role 角色信息
     * @return 角色数据集合信息
     */
    public List<SysRole> selectRoleList(SysRole role);

    /**
     * 根据用户ID查询角色
     * 
     * @param userId 用户ID
     * @return 角色列表
     */
    public List<SysRole> selectRolePermissionByUserId(Long userId);

    /**
     * 查询所有角色
     * 
     * @return 角色列表
     */
    public List<SysRole> selectRoleAll();

    /**
     * 根据用户ID获取角色选择框列表
     * 
     * @param userId 用户ID
     * @return 选中角色ID列表
     */
    public List<Long> selectRoleListByUserId(Long userId);

    /**
     * 通过角色ID查询角色
     * 
     * @param roleId 角色ID
     * @return 角色对象信息
     */
    public SysRole selectRoleById(Long roleId);

    /**
     * 根据用户ID查询角色
     * 
     * @param userName 用户名
     * @return 角色列表
     */
    public List<SysRole> selectRolesByUserName(String userName);

    /**
     * 校验角色名称是否唯一
     * 
     * @param roleName 角色名称
     * @return 角色信息
     */
    public SysRole checkRoleNameUnique(String roleName);

    /**
     * 校验角色权限是否唯一
     * 
     * @param roleKey 角色权限
     * @return 角色信息
     */
    public SysRole checkRoleKeyUnique(String roleKey);

    /**
     * 修改角色信息
     * 
     * @param role 角色信息
     * @return 结果
     */
    public int updateRole(SysRole role);

    /**
     * 新增角色信息
     * 
     * @param role 角色信息
     * @return 结果
     */
    public int insertRole(SysRole role);

    /**
     * 通过角色ID删除角色
     * 
     * @param roleId 角色ID
     * @return 结果
     */
    public int deleteRoleById(Long roleId);

    /**
     * 批量删除角色信息
     * 
     * @param roleIds 需要删除的角色ID
     * @return 结果
     */
    public int deleteRoleByIds(Long[] roleIds);

    /**
     * 查询回收站（软删除）角色列表
     * <p>
     * 仅取 del_flag='2' 的记录，复用列表可选过滤条件与数据范围，供回收站分页展示
     *
     * @param role 过滤条件
     * @return 已删除角色集合
     */
    public List<SysRole> selectDeletedRoleList(SysRole role);

    /**
     * 按主键查询回收站中的角色（仅 del_flag='2'），供恢复前取 role_key/role_name 做冲突校验
     *
     * @param roleId 角色ID
     * @return 回收站中的角色对象，不存在则 null
     */
    public SysRole selectDeletedRoleById(Long roleId);

    /**
     * 恢复单个软删除角色（del_flag 由 '2' 翻回 '0'），WHERE 带 del_flag='2' 前置，幂等防误翻
     *
     * @param roleId 角色ID
     * @return 影响行数
     */
    public int restoreRoleById(Long roleId);

    /**
     * 物理删除单个角色（回收站彻底删除，不可逆）
     * <p>
     * 关联 sys_role_menu/sys_role_dept 已在软删时清空，此处仅删主表行
     *
     * @param roleId 角色ID
     * @return 影响行数
     */
    public int deleteRoleByIdPhysically(Long roleId);
}
