package com.ruoyi.system.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.common.core.domain.entity.SysUser;

/**
 * 用户表 数据层
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public interface SysUserMapper {
    /**
     * 根据条件分页查询用户列表
     * 
     * @param sysUser 用户信息
     * @return 用户信息集合信息
     */
    public List<SysUser> selectUserList(SysUser sysUser);

    /**
     * 根据条件分页查询已配用户角色列表
     * 
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    public List<SysUser> selectAllocatedList(SysUser user);

    /**
     * 根据条件分页查询未分配用户角色列表
     * 
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    public List<SysUser> selectUnallocatedList(SysUser user);

    /**
     * 通过用户名查询用户
     * 
     * @param userName 用户名
     * @return 用户对象信息
     */
    public SysUser selectUserByUserName(String userName);

    /**
     * 通过用户ID查询用户
     * 
     * @param userId 用户ID
     * @return 用户对象信息
     */
    public SysUser selectUserById(Long userId);

    /**
     * 新增用户信息
     * 
     * @param user 用户信息
     * @return 结果
     */
    public int insertUser(SysUser user);

    /**
     * 修改用户信息
     * 
     * @param user 用户信息
     * @return 结果
     */
    public int updateUser(SysUser user);

    /**
     * 修改用户头像
     * 
     * @param userId 用户ID
     * @param avatar 头像地址
     * @return 结果
     */
    public int updateUserAvatar(@Param("userId") Long userId, @Param("avatar") String avatar);

    /**
     * 修改用户状态
     * 
     * @param userId 用户ID
     * @param status 状态
     * @return 结果
     */
    public int updateUserStatus(@Param("userId") Long userId, @Param("status") String status);

    /**
     * 更新用户登录信息（IP和登录时间）
     * 
     * @param userId 用户ID
     * @param loginIp 登录IP地址
     * @param loginDate 登录时间
     * @return 结果
     */
    public int updateLoginInfo(@Param("userId") Long userId, @Param("loginIp") String loginIp, @Param("loginDate") LocalDateTime loginDate);

    /**
     * 重置用户密码
     * 
     * @param userId 用户ID
     * @param password 密码
     * @return 结果
     */
    public int resetUserPwd(@Param("userId") Long userId, @Param("password") String password);

    /**
     * 通过用户ID删除用户
     * 
     * @param userId 用户ID
     * @return 结果
     */
    public int deleteUserById(Long userId);

    /**
     * 批量删除用户信息
     * 
     * @param userIds 需要删除的用户ID
     * @return 结果
     */
    public int deleteUserByIds(Long[] userIds);

    /**
     * 校验用户名称是否唯一
     * 
     * @param userName 用户名称
     * @return 结果
     */
    public SysUser checkUserNameUnique(String userName);

    /**
     * 校验手机号码是否唯一
     *
     * @param phonenumber 手机号码
     * @return 结果
     */
    public SysUser checkPhoneUnique(String phonenumber);

    /**
     * 校验email是否唯一
     *
     * @param email 用户邮箱
     * @return 结果
     */
    public SysUser checkEmailUnique(String email);

    /**
     * 查询回收站（软删除）用户列表
     * <p>
     * 仅取 del_flag='2' 的记录，复用列表可选过滤条件与数据范围（${params.dataScope}），供回收站分页展示
     *
     * @param sysUser 过滤条件
     * @return 已删除用户集合
     */
    public List<SysUser> selectDeletedUserList(SysUser sysUser);

    /**
     * 按主键查询回收站中的用户（仅 del_flag='2'）
     * <p>
     * 供恢复前取原始 user_name/dept_id 等字段做身份冲突与悬空引用校验
     *
     * @param userId 用户ID
     * @return 回收站中的用户对象，不存在则 null
     */
    public SysUser selectDeletedUserById(Long userId);

    /**
     * 恢复单个软删除用户（del_flag 由 '2' 翻回 '0'）
     * <p>
     * WHERE 带 del_flag='2' 前置条件，保证幂等且不会误翻活跃记录
     *
     * @param userId 用户ID
     * @return 影响行数（1 恢复成功，0 表示该 id 不在回收站）
     */
    public int restoreUserById(Long userId);

    /**
     * 物理删除单个用户（回收站彻底删除，不可逆）
     * <p>
     * 关联 sys_user_role/sys_user_post 已在软删时清空，此处仅删主表行
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    public int deleteUserByIdPhysically(Long userId);
}
