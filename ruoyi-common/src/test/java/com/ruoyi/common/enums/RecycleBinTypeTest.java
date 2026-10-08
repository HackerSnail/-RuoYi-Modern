package com.ruoyi.common.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RecycleBinType 元数据与白名单解析测试。
 *
 * <p>
 * 锁定回收站类型枚举作为 controller 入口白名单与权限串派生的契约：fromCode 对合法编码返回对应枚举、
 * 对非法编码（含 null/空/未知）一律抛 IllegalArgumentException，杜绝非法 type 进入后续分派；
 * restorePermission/purgePermission 由 permPrefix 派生出固定串，LIST_PERMISSION 为三类共用查看位。
 * </p>
 */
public class RecycleBinTypeTest {
    @Test
    @DisplayName("fromCode：合法编码 user/role/dept 各自解析")
    void fromCodeResolvesValidCodes() {
        assertEquals(RecycleBinType.USER, RecycleBinType.fromCode("user"));
        assertEquals(RecycleBinType.ROLE, RecycleBinType.fromCode("role"));
        assertEquals(RecycleBinType.DEPT, RecycleBinType.fromCode("dept"));
    }

    @Test
    @DisplayName("fromCode：null/空/未知编码一律拒绝（白名单）")
    void fromCodeRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> RecycleBinType.fromCode(null));
        assertThrows(IllegalArgumentException.class, () -> RecycleBinType.fromCode(""));
        assertThrows(IllegalArgumentException.class, () -> RecycleBinType.fromCode("bogus"));
        // 大小写敏感：非白名单原样值不得命中
        assertThrows(IllegalArgumentException.class, () -> RecycleBinType.fromCode("USER"));
    }

    @Test
    @DisplayName("权限串：由 permPrefix 派生 restore/purge 固定串，共用 list 查看位")
    void derivedPermissionStrings() {
        assertEquals("system:user:restore", RecycleBinType.USER.restorePermission());
        assertEquals("system:user:purge", RecycleBinType.USER.purgePermission());
        assertEquals("system:role:restore", RecycleBinType.ROLE.restorePermission());
        assertEquals("system:dept:purge", RecycleBinType.DEPT.purgePermission());
        assertEquals("system:recyclebin:list", RecycleBinType.LIST_PERMISSION);
    }

    @Test
    @DisplayName("元数据：code/displayName/pkField/permPrefix 逐字对齐既有表与权限")
    void metadataAccessors() {
        assertEquals("user", RecycleBinType.USER.getCode());
        assertEquals("用户", RecycleBinType.USER.getDisplayName());
        assertEquals("user_id", RecycleBinType.USER.getPkField());
        assertEquals("system:user", RecycleBinType.USER.getPermPrefix());
        assertEquals("role_id", RecycleBinType.ROLE.getPkField());
        assertEquals("dept_id", RecycleBinType.DEPT.getPkField());
    }
}
