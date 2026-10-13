package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.TreeSelect;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.service.ISysDeptService;

/**
 * 部门管理 服务实现
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class SysDeptServiceImpl implements ISysDeptService {
    private final SysDeptMapper deptMapper;

    private final SysRoleMapper roleMapper;

    public SysDeptServiceImpl(SysDeptMapper deptMapper, SysRoleMapper roleMapper) {
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 查询部门管理数据
     * 
     * @param dept 部门信息
     * @return 部门信息集合
     */
    @Override
    @DataScope(deptAlias = "d")
    public List<SysDept> selectDeptList(SysDept dept) {
        return deptMapper.selectDeptList(dept);
    }

    /**
     * 查询部门树结构信息
     * 
     * @param dept 部门信息
     * @return 部门树信息集合
     */
    @Override
    public List<TreeSelect> selectDeptTreeList(SysDept dept) {
        List<SysDept> depts = SpringUtils.getAopProxy(this).selectDeptList(dept);
        return buildDeptTreeSelect(depts);
    }

    /**
     * 构建前端所需要树结构
     * 
     * @param depts 部门列表
     * @return 树结构列表
     */
    @Override
    public List<SysDept> buildDeptTree(List<SysDept> depts) {
        List<SysDept> returnList = new ArrayList<>();
        List<Long> tempList = depts.stream().map(SysDept::getDeptId).collect(Collectors.toList());
        for (SysDept dept : depts) {
            // 如果是顶级节点, 遍历该父节点的所有子节点（其 parentId 不在本次结果集内即视为顶级）
            if (!tempList.contains(dept.getParentId())) {
                recursionFn(depts, dept);
                returnList.add(dept);
            }
        }
        if (returnList.isEmpty()) {
            returnList = depts;
        }
        return returnList;
    }

    /**
     * 构建前端所需要下拉树结构
     * 
     * @param depts 部门列表
     * @return 下拉树结构列表
     */
    @Override
    public List<TreeSelect> buildDeptTreeSelect(List<SysDept> depts) {
        List<SysDept> deptTrees = buildDeptTree(depts);
        return deptTrees.stream().map(TreeSelect::new).collect(Collectors.toList());
    }

    /**
     * 根据角色ID查询部门树信息
     * 
     * @param roleId 角色ID
     * @return 选中部门列表
     */
    @Override
    public List<Long> selectDeptListByRoleId(Long roleId) {
        SysRole role = roleMapper.selectRoleById(roleId);
        // 回查为空（roleId 不存在/已被删除）直接抛业务异常，避免 role.isDeptCheckStrictly() NPE 500
        if (StringUtils.isNull(role)) {
            throw new ServiceException("角色不存在或已被删除");
        }
        return deptMapper.selectDeptListByRoleId(roleId, role.isDeptCheckStrictly());
    }

    /**
     * 根据部门ID查询信息
     * 
     * @param deptId 部门ID
     * @return 部门信息
     */
    @Override
    public SysDept selectDeptById(Long deptId) {
        // 数据权限收口到 Service：不依赖调用方“记得先调 checkDeptDataScope”（Controller 已调则冗余不致命）
        checkDeptDataScope(deptId);
        return deptMapper.selectDeptById(deptId);
    }

    /**
     * 根据ID查询所有子部门（正常状态）
     * 
     * @param deptId 部门ID
     * @return 子部门数
     */
    @Override
    public int selectNormalChildrenDeptById(Long deptId) {
        return deptMapper.selectNormalChildrenDeptById(deptId);
    }

    /**
     * 是否存在子节点
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    @Override
    public boolean hasChildByDeptId(Long deptId) {
        int result = deptMapper.hasChildByDeptId(deptId);
        return result > 0;
    }

    /**
     * 查询部门是否存在用户
     * 
     * @param deptId 部门ID
     * @return 结果 true 存在 false 不存在
     */
    @Override
    public boolean checkDeptExistUser(Long deptId) {
        int result = deptMapper.checkDeptExistUser(deptId);
        return result > 0;
    }

    /**
     * 校验部门名称是否唯一
     * 
     * @param dept 部门信息
     * @return 结果
     */
    @Override
    public boolean checkDeptNameUnique(SysDept dept) {
        Long deptId = StringUtils.isNull(dept.getDeptId()) ? -1L : dept.getDeptId();
        SysDept info = deptMapper.checkDeptNameUnique(dept.getDeptName(), dept.getParentId());
        if (StringUtils.isNotNull(info) && info.getDeptId().longValue() != deptId.longValue()) {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 校验部门是否有数据权限
     * 
     * @param deptId 部门id
     */
    @Override
    public void checkDeptDataScope(Long deptId) {
        if (!SecurityUtils.isAdmin() && StringUtils.isNotNull(deptId)) {
            SysDept dept = new SysDept();
            dept.setDeptId(deptId);
            List<SysDept> depts = SpringUtils.getAopProxy(this).selectDeptList(dept);
            if (StringUtils.isEmpty(depts)) {
                throw new ServiceException("没有权限访问部门数据！");
            }
        }
    }

    /**
     * 新增保存部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    @Override
    public int insertDept(SysDept dept) {
        // 数据范围收口：新部门只能挂在操作者有权管理的父节点下（防往无权/越权父节点挂子）
        checkDeptDataScope(dept.getParentId());
        SysDept info = deptMapper.selectDeptById(dept.getParentId());
        // 父节点不存在（非法 parentId/被并发删除）直接拒绝：避免 info.getStatus() NPE 500 与借道探测父节点
        if (StringUtils.isNull(info)) {
            throw new ServiceException("上级部门不存在或已被删除");
        }
        // 如果父节点不为正常状态,则不允许新增子节点
        if (!UserConstants.DEPT_NORMAL.equals(info.getStatus())) {
            throw new ServiceException("部门停用，不允许新增");
        }
        dept.setAncestors(info.getAncestors() + "," + dept.getParentId());
        return deptMapper.insertDept(dept);
    }

    /**
     * 修改保存部门信息
     * 
     * @param dept 部门信息
     * @return 结果
     */
    @Override
    public int updateDept(SysDept dept) {
        SysDept newParentDept = deptMapper.selectDeptById(dept.getParentId());
        SysDept oldDept = deptMapper.selectDeptById(dept.getDeptId());
        // 部门自身不存在（非法 deptId/被并发删除）直接拒绝
        if (StringUtils.isNull(oldDept)) {
            throw new ServiceException("部门不存在或已被删除");
        }
        // 新父节点必须存在：否则拒绝，绝不“parentId 已改而 ancestors 停旧链”静默写脏数据
        if (StringUtils.isNull(newParentDept)) {
            throw new ServiceException("上级部门不存在或已被删除");
        }
        // 环检测：新父不能是自己，也不能是自己的子孙（否则祖级链成环、后代 ancestors 被改坏）
        if (dept.getDeptId().equals(dept.getParentId())) {
            throw new ServiceException("上级部门不能是自己");
        }
        if (StringUtils.contains("," + newParentDept.getAncestors() + ",", "," + dept.getDeptId() + ",")) {
            throw new ServiceException("上级部门不能是自己的下级部门");
        }
        // 数据范围收口：目标部门与新父节点都必须在操作者数据范围内（防把所辖部门迁移到无权节点=范围逃逸）
        checkDeptDataScope(dept.getDeptId());
        checkDeptDataScope(dept.getParentId());
        String newAncestors = newParentDept.getAncestors() + "," + newParentDept.getDeptId();
        String oldAncestors = oldDept.getAncestors();
        dept.setAncestors(newAncestors);
        updateDeptChildren(dept.getDeptId(), newAncestors, oldAncestors);
        // 取舍（#4 收敛）：原“下级改正常即无条件反写整条祖级链为启用”会让被刻意停用的上级被连带复活（越权副作用），
        // 已移除：部门启用/停用由各自编辑显式管理，不再借一次子部门保存静默启用无权/被停用的上级。
        return deptMapper.updateDept(dept);
    }

    /**
     * 修改子元素关系
     * 
     * @param deptId       被修改的部门ID
     * @param newAncestors 新的父ID集合
     * @param oldAncestors 旧的父ID集合
     */
    public void updateDeptChildren(Long deptId, String newAncestors, String oldAncestors) {
        List<SysDept> children = deptMapper.selectChildrenDeptById(deptId);
        for (SysDept child : children) {
            // 前缀整体替换（非正则）：后代 ancestors 必以 oldAncestors 开头，剪掉该前缀拼上 newAncestors 即可，
            // 避免把 oldAncestors 当正则传入 replaceFirst 未来祖级链格式变化时踩元字符误解析
            String anc = child.getAncestors();
            if (StringUtils.isNotEmpty(anc) && StringUtils.isNotEmpty(oldAncestors) && anc.startsWith(oldAncestors)) {
                child.setAncestors(newAncestors + anc.substring(oldAncestors.length()));
            }
        }
        if (children.size() > 0) {
            deptMapper.updateDeptChildren(children);
        }
    }

    /**
     * 保存部门排序（批量优化）
     *
     * <p>
     * 将原来的 N 次单行 UPDATE 合并为 1 次 CASE WHEN 批量 UPDATE，
     * 减少网络往返且保证原子性（@Transactional）。
     * </p>
     *
     * @param deptIds   部门ID数组
     * @param orderNums 对应的新排序号数组，与 deptIds 按下标一一对应
     */
    @Override
    @Transactional
    public void updateDeptSort(String[] deptIds, String[] orderNums) {
        // 先校验两数组长度一致（前端传参不齐会按下标错位配对），拒绝时给明确文案而非越界堆栈
        if (deptIds == null || orderNums == null || deptIds.length != orderNums.length) {
            throw new ServiceException("保存排序失败，部门与排序号数量不一致");
        }
        // 越权防护：逐个校验目标部门在操作者数据范围内（置于 try 外，避免权限异常被下方 catch 兵成通用“保存排序异常”）
        for (String deptId : deptIds) {
            Long id = Convert.toLong(deptId);
            if (id == null) {
                throw new ServiceException("保存排序失败，部门 ID 非法");
            }
            checkDeptDataScope(id);
        }
        try {
            List<SysDept> deptList = new ArrayList<>();
            for (int i = 0; i < deptIds.length; i++) {
                SysDept dept = new SysDept();
                dept.setDeptId(Convert.toLong(deptIds[i]));
                dept.setOrderNum(Convert.toInt(orderNums[i]));
                deptList.add(dept);
            }
            if (!deptList.isEmpty()) {
                deptMapper.updateDeptSortBatch(deptList);
            }
        } catch (Exception e) {
            throw new ServiceException("保存排序异常，请联系管理员");
        }
    }

    /**
     * 删除部门管理信息
     * 
     * @param deptId 部门ID
     * @return 结果
     */
    @Override
    public int deleteDeptById(Long deptId) {
        // 数据权限收口：只能删自己有权管理的部门（Controller 已拦则冗余不致命）
        checkDeptDataScope(deptId);
        // 删除前护栏收口：存在未删除子部门 或 部门下仍有在职用户则拒绝（防孤儿数据，不依赖调用方记得拦）
        if (hasChildByDeptId(deptId)) {
            throw new ServiceException("存在下级部门，不允许删除");
        }
        if (checkDeptExistUser(deptId)) {
            throw new ServiceException("部门存在用户，不允许删除");
        }
        return deptMapper.deleteDeptById(deptId);
    }

    /**
     * 递归列表
     */
    private void recursionFn(List<SysDept> list, SysDept t) {
        // 得到子节点列表
        List<SysDept> childList = getChildList(list, t);
        t.setChildren(childList);
        for (SysDept tChild : childList) {
            if (hasChild(list, tChild)) {
                recursionFn(list, tChild);
            }
        }
    }

    /**
     * 得到子节点列表
     */
    private List<SysDept> getChildList(List<SysDept> list, SysDept t) {
        List<SysDept> tlist = new ArrayList<>();
        for (SysDept n : list) {
            if (StringUtils.isNotNull(n.getParentId()) && n.getParentId().longValue() == t.getDeptId().longValue()) {
                tlist.add(n);
            }
        }
        return tlist;
    }

    /**
     * 判断是否有子节点
     */
    private boolean hasChild(List<SysDept> list, SysDept t) {
        return getChildList(list, t).size() > 0;
    }
}
