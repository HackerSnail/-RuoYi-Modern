package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.RecycleBinType;
import com.ruoyi.system.domain.dto.RecycleBinOpResult;

/**
 * 回收站 业务层
 * <p>
 * 面向软删除（del_flag='2'）的用户/角色/部门，提供列表、恢复、彻底删除三类能力。
 * 列表按类型分三个方法（各自挂 @DataScope，别名与对应 selectXxxList 一致）；
 * 恢复/彻底删除保持类型无关，按 RecycleBinType 在服务层内部分派，逐项独立处理并汇总结果。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public interface IRecycleBinService {
    /**
     * 查询回收站中的用户列表（仅 del_flag='2'，带数据范围过滤）
     *
     * @param user 过滤条件
     * @return 已删除用户集合
     */
    public List<SysUser> selectDeletedUserList(SysUser user);

    /**
     * 查询回收站中的角色列表（仅 del_flag='2'，带数据范围过滤）
     *
     * @param role 过滤条件
     * @return 已删除角色集合
     */
    public List<SysRole> selectDeletedRoleList(SysRole role);

    /**
     * 查询回收站中的部门列表（仅 del_flag='2'，带数据范围过滤）
     *
     * @param dept 过滤条件
     * @return 已删除部门集合
     */
    public List<SysDept> selectDeletedDeptList(SysDept dept);

    /**
     * 批量恢复软删除记录（del_flag 由 '2' 翻回 '0'）
     * <p>
     * 逐项独立处理、部分成功：任一校验不过仅该项失败，不影响批内其他项。
     *
     * @param type 回收站类型
     * @param ids  目标主键数组
     * @return 逐项结果
     */
    public RecycleBinOpResult restore(RecycleBinType type, Long[] ids);

    /**
     * 批量彻底删除软删除记录（物理 DELETE，不可逆）
     * <p>
     * 需二次确认：confirm 非 true 时整请求拒绝；通过后逐项独立处理、部分成功。
     *
     * @param type    回收站类型
     * @param ids     目标主键数组
     * @param confirm 二次确认标志
     * @return 逐项结果
     */
    public RecycleBinOpResult purge(RecycleBinType type, Long[] ids, boolean confirm);
}
