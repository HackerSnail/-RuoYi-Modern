package com.ruoyi.web.controller.system;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.RecycleBinType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.domain.dto.RecycleBinBatchReq;
import com.ruoyi.system.service.IRecycleBinService;

/**
 * 回收站（软删除记录的列表/恢复/彻底删除）
 * <p>
 * 三类实体共用一个控制器，以路径变量 {type} 分派。因 {type} 运行时才确定、而 @PreAuthorize 权限串须编译期常量，
 * 故鉴权在方法体内程序化完成：先 RecycleBinType.fromCode 白名单校验（非法 type 直接拒），再据枚举派生的固定权限串
 * 调 PermissionService.hasPermi 断言——白名单先于权限串，杜绝把用户输入拼进鉴权（见 spec §12）。
 * 恢复/彻底删除返回逐项结果 RecycleBinOpResult，部分成功以 HTTP 200 承载。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/system/recyclebin")
public class SysRecycleBinController extends BaseController {
    private final IRecycleBinService recycleBinService;

    private final PermissionService permissionService;

    public SysRecycleBinController(IRecycleBinService recycleBinService, PermissionService permissionService) {
        this.recycleBinService = recycleBinService;
        this.permissionService = permissionService;
    }

    /**
     * 分页列出回收站中的软删除记录（仅 del_flag='2'，按当前用户数据范围过滤）
     */
    @GetMapping("/{type}/list")
    public TableDataInfo list(@PathVariable String type, @RequestParam(required = false) String keyword) {
        RecycleBinType rbType = parseType(type);
        assertPermi(RecycleBinType.LIST_PERMISSION);
        startPage();
        return getDataTable(queryDeletedList(rbType, keyword));
    }

    /**
     * 批量恢复软删除记录（del_flag 翻回 '0'），逐项返回结果
     */
    @Log(title = "回收站", businessType = BusinessType.UPDATE)
    @PutMapping("/{type}/restore")
    public AjaxResult restore(@PathVariable String type, @RequestBody RecycleBinBatchReq req) {
        RecycleBinType rbType = parseType(type);
        assertPermi(rbType.restorePermission());
        return success(recycleBinService.restore(rbType, req.getIds()));
    }

    /**
     * 批量彻底删除软删除记录（物理删除，不可逆），需 confirm=true 二次确认
     */
    @Log(title = "回收站", businessType = BusinessType.DELETE)
    @DeleteMapping("/{type}/purge")
    public AjaxResult purge(@PathVariable String type, @RequestBody RecycleBinBatchReq req) {
        RecycleBinType rbType = parseType(type);
        assertPermi(rbType.purgePermission());
        boolean confirm = Boolean.TRUE.equals(req.getConfirm());
        return success(recycleBinService.purge(rbType, req.getIds(), confirm));
    }

    /** 解析并白名单校验类型，非法值转业务异常（非 500） */
    private RecycleBinType parseType(String type) {
        try {
            return RecycleBinType.fromCode(type);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("非法的回收站类型：" + type);
        }
    }

    /** 程序化权限断言：不通过抛 AccessDeniedException（由 Spring Security 转 403） */
    private void assertPermi(String permission) {
        if (!permissionService.hasPermi(permission)) {
            throw new AccessDeniedException("没有权限，请联系管理员授权");
        }
    }

    /** 按类型分派到对应的回收站列表查询（keyword 落到各实体的名称字段做模糊过滤） */
    private List<?> queryDeletedList(RecycleBinType type, String keyword) {
        switch (type) {
            case USER: {
                SysUser user = new SysUser();
                if (StringUtils.isNotEmpty(keyword)) {
                    user.setUserName(keyword);
                }
                return recycleBinService.selectDeletedUserList(user);
            }
            case ROLE: {
                SysRole role = new SysRole();
                if (StringUtils.isNotEmpty(keyword)) {
                    role.setRoleName(keyword);
                }
                return recycleBinService.selectDeletedRoleList(role);
            }
            case DEPT: {
                SysDept dept = new SysDept();
                if (StringUtils.isNotEmpty(keyword)) {
                    dept.setDeptName(keyword);
                }
                return recycleBinService.selectDeletedDeptList(dept);
            }
            default:
                throw new ServiceException("不支持的回收站类型：" + type);
        }
    }
}
