package com.ruoyi.common.enums;

/**
 * 回收站实体类型
 * <p>
 * 仅承载与具体表相关的纯元数据（编码/展示名/主键字段/权限前缀），使 controller/service
 * 可保持类型无关；type 到各 Mapper 的实际分派在 ruoyi-system 的服务层完成，
 * 此处不引用任何 system 层类型，避免 common 反向依赖 system。
 *
 * @author ruoyi
 */
public enum RecycleBinType {
    /** 用户 */
    USER("user", "用户", "user_id", "system:user"),
    /** 角色 */
    ROLE("role", "角色", "role_id", "system:role"),
    /** 部门 */
    DEPT("dept", "部门", "dept_id", "system:dept");

    /** 回收站查看权限（三类共用一个查看位） */
    public static final String LIST_PERMISSION = "system:recyclebin:list";

    /** 类型编码（URL 路径变量 {type} 与白名单校验用） */
    private final String code;
    /** 展示名（结果提示/前端文案用） */
    private final String displayName;
    /** 主键字段名（对应表列，物理删/恢复 SQL 用） */
    private final String pkField;
    /** 权限前缀（拼 restore/purge 动作权限用） */
    private final String permPrefix;

    RecycleBinType(String code, String displayName, String pkField, String permPrefix) {
        this.code = code;
        this.displayName = displayName;
        this.pkField = pkField;
        this.permPrefix = permPrefix;
    }

    /**
     * 按编码解析类型，作为 controller 入口的白名单校验
     *
     * @param code 路径变量 {type} 原始值
     * @return 匹配的类型
     * @throws IllegalArgumentException 编码非法（非 user/role/dept）时抛出，杜绝非法 type 进入后续分派与权限串拼接
     */
    public static RecycleBinType fromCode(String code) {
        for (RecycleBinType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("非法的回收站类型：" + code);
    }

    /** 恢复动作权限串，如 system:user:restore */
    public String restorePermission() {
        return permPrefix + ":restore";
    }

    /** 彻底删除动作权限串，如 system:user:purge */
    public String purgePermission() {
        return permPrefix + ":purge";
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPkField() {
        return pkField;
    }

    public String getPermPrefix() {
        return permPrefix;
    }
}
