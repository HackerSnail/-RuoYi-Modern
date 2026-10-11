package com.ruoyi.framework.aspectj;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.security.context.PermissionContextHolder;

/**
 * 数据过滤处理
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Aspect
@Component
public class DataScopeAspect {
    /**
     * 数据权限过滤关键字
     */
    public static final String DATA_SCOPE = "dataScope";

    /**
     * 合法 SQL 标识符(表别名/字段名)白名单：仅允许字母数字下划线与点号。
     * 这些值来自 @DataScope 注解字面量，此处显式校验把“恒可信”变成护栏，防未来接上更弱的来源。
     */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    /**
     * 前置增强：先清空可能被打入的 dataScope 参数（防注入），再根据当前用户角色拼接数据过滤 SQL
     */
    @Before("@annotation(controllerDataScope)")
    public void doBefore(JoinPoint point, DataScope controllerDataScope) throws Throwable {
        clearDataScope(point);
        handleDataScope(point, controllerDataScope);
    }

    protected void handleDataScope(final JoinPoint joinPoint, DataScope controllerDataScope) {
        // 获取当前的用户
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNotNull(loginUser)) {
            SysUser currentUser = loginUser.getUser();
            // 如果是超级管理员，则不过滤数据
            if (StringUtils.isNotNull(currentUser) && !currentUser.isAdmin()) {
                String permission = StringUtils.defaultIfEmpty(controllerDataScope.permission(),
                        PermissionContextHolder.getContext());
                dataScopeFilter(joinPoint, currentUser, controllerDataScope.userAlias(),
                        controllerDataScope.deptAlias(), controllerDataScope.userField(),
                        controllerDataScope.deptField(), permission);
            }
        }
    }

    /**
     * 数据范围过滤
     *
     * @param joinPoint  切点
     * @param user       用户
     * @param deptAlias  部门别名
     * @param userAlias  用户别名
     * @param permission 权限字符
     */
    public static void dataScopeFilter(JoinPoint joinPoint, SysUser user, String userAlias, String deptAlias,
            String userField, String deptField, String permission) {
        // 纵深防御：拼接前先白名单校验 4 个 SQL 标识符(空值放行，如未提供的 userAlias)；
        // roleId/deptId/userId 均为 Long，拼进 SQL 只能是数字，天然无注入面。
        deptAlias = safeAlias(deptAlias);
        userAlias = safeAlias(userAlias);
        deptField = safeAlias(deptField);
        userField = safeAlias(userField);

        StringBuilder sqlString = new StringBuilder();
        List<String> conditions = new ArrayList<>();
        // 阶段1：收集拥有“自定义”数据权限、角色正常且匹配当前权限字符的角色 ID(以 Long 收集，避免拼字符串)
        List<Long> scopeCustomIds = new ArrayList<>();
        for (SysRole role : user.getRoles()) {
            if (Constants.Dept.DATA_SCOPE_CUSTOM.equals(role.getDataScope())
                    && StringUtils.equals(role.getStatus(), UserConstants.ROLE_NORMAL)
                    && (StringUtils.isEmpty(permission)
                            || StringUtils.containsAny(role.getPermissions(), Convert.toStrArray(permission)))) {
                scopeCustomIds.add(role.getRoleId());
            }
        }

        // 阶段2：遍历角色拼接 SQL；CUSTOM 统一用阶段1 预收集的 ID 列表，主循环不再回写 scopeCustomIds
        for (SysRole role : user.getRoles()) {
            String dataScope = role.getDataScope();
            // 同一数据权限只拼接一次（conditions 去重）；停用角色直接跳过
            if (conditions.contains(dataScope) || StringUtils.equals(role.getStatus(), UserConstants.ROLE_DISABLE)) {
                continue;
            }
            if (StringUtils.isNotEmpty(permission)
                    && !StringUtils.containsAny(role.getPermissions(), Convert.toStrArray(permission))) {
                continue;
            }
            if (Constants.Dept.DATA_SCOPE_ALL.equals(dataScope)) {
                // “全部数据权限”= 不加任何过滤：清空已拼接条件并短路退出循环。
                // 之后 sqlString 为空 -> 不写入 DATA_SCOPE 参数 -> 查询无过滤；conditions 保留 DATA_SCOPE_ALL
                // 使下方 isEmpty(conditions) 兜底不触发(超级角色可见全部，不应被追加 “= 0” 限制)。
                sqlString = new StringBuilder();
                conditions.add(dataScope);
                break;
            } else if (Constants.Dept.DATA_SCOPE_CUSTOM.equals(dataScope)) {
                if (scopeCustomIds.size() > 1) {
                    // 多个自定数据权限使用 in 查询，避免多次拼接。ID 均为 Long，拼出来即纯数字。
                    sqlString.append(StringUtils.format(
                            " OR {}.{} IN ( SELECT dept_id FROM sys_role_dept WHERE role_id in ({}) ) ", deptAlias,
                            deptField,
                            scopeCustomIds.stream().map(String::valueOf).collect(Collectors.joining(","))));
                } else {
                    sqlString.append(
                            StringUtils.format(" OR {}.{} IN ( SELECT dept_id FROM sys_role_dept WHERE role_id = {} ) ",
                                    deptAlias, deptField, role.getRoleId()));
                }
            } else if (Constants.Dept.DATA_SCOPE_DEPT.equals(dataScope)) {
                sqlString.append(StringUtils.format(" OR {}.{} = {} ", deptAlias, deptField, user.getDeptId()));
            } else if (Constants.Dept.DATA_SCOPE_DEPT_AND_CHILD.equals(dataScope)) {
                sqlString.append(StringUtils.format(
                        " OR {}.{} IN ( SELECT dept_id FROM sys_dept WHERE dept_id = {} or find_in_set( {} , ancestors ) )",
                        deptAlias, deptField, user.getDeptId(), user.getDeptId()));
            } else if (Constants.Dept.DATA_SCOPE_SELF.equals(dataScope)) {
                if (StringUtils.isNotBlank(userAlias)) {
                    sqlString.append(StringUtils.format(" OR {}.{} = {} ", userAlias, userField, user.getUserId()));
                } else {
                    // 数据权限为仅本人且没有userAlias别名不查询任何数据
                    sqlString.append(StringUtils.format(" OR {}.{} = 0 ", deptAlias, deptField));
                }
            }
            conditions.add(dataScope);
        }

        // 角色都不包含传递过来的权限字符，这个时候sqlString也会为空，所以要限制一下,不查询任何数据
        if (StringUtils.isEmpty(conditions)) {
            sqlString.append(StringUtils.format(" OR {}.{} = 0 ", deptAlias, deptField));
        }

        if (StringUtils.isNotBlank(sqlString.toString())) {
            // 切面防御：@DataScope 约定标注方法首参为 BaseEntity，但无编译期强制，空参时早返回避免 AIOOBE
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) {
                return;
            }
            Object params = args[0];
            if (StringUtils.isNotNull(params) && params instanceof BaseEntity baseEntity) {
                // substring(4) 去掉首部多余的分隔“ OR ”，再以 AND (...) 包裹作为独立条件注入查询参数
                baseEntity.getParams().put(DATA_SCOPE, " AND (" + sqlString.substring(4) + ")");
            }
        }
    }

    /**
     * 校验并放行 SQL 标识符(表别名/字段名)：空白原样返回(如未提供的 userAlias)，
     * 非空白但不匹配白名单则抛异常，避免把不可信内容拼进 SQL。
     */
    private static String safeAlias(String alias) {
        if (StringUtils.isBlank(alias)) {
            return alias;
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new IllegalArgumentException("非法数据权限别名/字段: " + alias);
        }
        return alias;
    }

    /**
     * 拼接权限sql前先清空params.dataScope参数防止注入
     */
    private void clearDataScope(final JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        Object params = args[0];
        if (StringUtils.isNotNull(params) && params instanceof BaseEntity baseEntity) {
            baseEntity.getParams().put(DATA_SCOPE, "");
        }
    }
}
