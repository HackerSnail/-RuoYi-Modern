package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.common.core.domain.entity.SysDept;

/**
 * 部门管理 数据层
 * 
 * @author ruoyi
 */
public interface SysDeptMapper {
    /**
     * 查询部门管理数据
     * 
     * @param dept 部门信息
     * @return 部门信息集合
     */
    public List<SysDept> selectDeptList(SysDept dept);

    /**
     * 根据角色ID查询部门树信息
     * 
     * @param roleId            角色ID
     * @param deptCheckStrictly 部门树选择项是否关联显示
     * @return 选中部门列表
     */
    public List<Long> selectDeptListByRoleId(@Param("roleId") Long roleId,
            @Param("deptCheckStrictly") boolean deptCheckStrictly);

    /**
     * 根据部门ID查询信息
     * 
     * @param deptId 部门ID
     * @return 部门信息
     */
    public SysDept selectDeptById(Long deptId);

    /**
     * 根据ID查询所有子部门
     * 
     * @param deptId 部门ID
     * @return 部门列表
     */
    public List<SysDept> selectChildrenDeptById(Long deptId);

    /**
     * 根据ID查询所有子部门（正常状态）
     * 
     * @param deptId 部门ID
     * @return 子部门数
     */
    public int selectNormalChildrenDeptById(Long deptId);

    /**
     * 是否存在子节点
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    public int hasChildByDeptId(Long deptId);

    /**
     * 统计回收站（del_flag='2'）中该部门的直系子部门数量（物理销毁父级前防子级永久悬空）
     *
     * @param deptId 部门ID
     * @return 回收站中的直系子部门数量
     */
    public int hasRecycledChildByDeptId(Long deptId);

    /**
     * 统计回收站（del_flag='2'）中挂在该部门下的用户数量（物理销毁部门前防用户永久悬空）
     *
     * @param deptId 部门ID
     * @return 回收站中该部门下的用户数量
     */
    public int countRecycledUserByDeptId(Long deptId);

    /**
     * 查询部门是否存在用户
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    public int checkDeptExistUser(Long deptId);

    /**
     * 校验部门名称是否唯一
     * 
     * @param deptName 部门名称
     * @param parentId 父部门ID
     * @return 结果
     */
    public SysDept checkDeptNameUnique(@Param("deptName") String deptName, @Param("parentId") Long parentId);

    /**
     * 新增部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    public int insertDept(SysDept dept);

    /**
     * 修改部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    public int updateDept(SysDept dept);

    /**
     * 修改所在部门正常状态
     * 
     * @param deptIds 部门ID组
     */
    public void updateDeptStatusNormal(Long[] deptIds);

    /**
     * 修改子元素关系
     * 
     * @param depts 子元素
     * @return 结果
     */
    public int updateDeptChildren(@Param("depts") List<SysDept> depts);

    /**
     * 保存部门排序
     *
     * @param dept 部门信息
     */
    public void updateDeptSort(SysDept dept);

    /**
     * 批量保存部门排序（单条 CASE 批量 UPDATE，替代逐行 updateDeptSort）
     *
     * @param deptList 部门排序信息集合
     */
    public void updateDeptSortBatch(@Param("list") List<SysDept> deptList);

    /**
     * 删除部门管理信息
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    public int deleteDeptById(Long deptId);

    /**
     * 查询回收站（软删除）部门列表
     * <p>
     * 仅取 del_flag='2' 的记录，复用列表可选过滤条件与数据范围，供回收站分页展示
     *
     * @param dept 过滤条件
     * @return 已删除部门集合
     */
    public List<SysDept> selectDeletedDeptList(SysDept dept);

    /**
     * 按主键查询回收站中的部门（仅 del_flag='2'），供恢复前取 parent_id/dept_name 做冲突与悬空校验
     *
     * @param deptId 部门ID
     * @return 回收站中的部门对象，不存在则 null
     */
    public SysDept selectDeletedDeptById(Long deptId);

    /**
     * 恢复单个软删除部门（del_flag 由 '2' 翻回 '0'），WHERE 带 del_flag='2' 前置，幂等防误翻
     *
     * @param deptId 部门ID
     * @return 影响行数
     */
    public int restoreDeptById(Long deptId);

    /**
     * 物理删除单个部门（回收站彻底删除，不可逆；部门无关联表）
     *
     * @param deptId 部门ID
     * @return 影响行数
     */
    public int deleteDeptByIdPhysically(Long deptId);
}
