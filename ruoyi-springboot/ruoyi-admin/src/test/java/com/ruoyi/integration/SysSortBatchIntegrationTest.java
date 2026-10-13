package com.ruoyi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysMenuMapper;

/**
 * 批量排序 UPDATE 的集成测试：在真实 MySQL 上执行 {@code updateDeptSortBatch} /
 * {@code updateMenuSortBatch} 这两条 CASE 批量语句，验证命中行各取各值、未命中对照行不受影响。
 * 用例自建部门/菜单 fixture，方法级事务结束后自动回滚，对库零残留。
 */
@DisplayName("批量排序 UPDATE：dept / menu（真实 MySQL，事务回滚）")
public class SysSortBatchIntegrationTest extends BaseMapperIntegrationTest {
    @Autowired
    private SysDeptMapper deptMapper;

    @Autowired
    private SysMenuMapper menuMapper;

    @Test
    @DisplayName("updateDeptSortBatch 仅更新命中行，对照行不变")
    void updateDeptSortBatchUpdatesOnlyTargetedRows() {
        String tag = "it_dept_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long a = insertDept(tag + "_a", 1);
        Long b = insertDept(tag + "_b", 2);
        Long c = insertDept(tag + "_c", 3);
        Long control = insertDept(tag + "_ctl", 4);

        List<SysDept> list = new ArrayList<>();
        list.add(dept(a, 11));
        list.add(dept(b, 22));
        list.add(dept(c, 33));
        deptMapper.updateDeptSortBatch(list);

        assertEquals(11, deptOrder(a));
        assertEquals(22, deptOrder(b));
        assertEquals(33, deptOrder(c));
        assertEquals(4, deptOrder(control), "不在 in(...) 内的对照行必须保持不变");
    }

    @Test
    @DisplayName("updateMenuSortBatch 仅更新命中行，对照行不变")
    void updateMenuSortBatchUpdatesOnlyTargetedRows() {
        String tag = "it_menu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long a = insertMenu(tag + "_a", 1);
        Long b = insertMenu(tag + "_b", 2);
        Long control = insertMenu(tag + "_ctl", 3);

        List<SysMenu> list = new ArrayList<>();
        list.add(menu(a, 101));
        list.add(menu(b, 202));
        menuMapper.updateMenuSortBatch(list);

        assertEquals(101, menuOrder(a));
        assertEquals(202, menuOrder(b));
        assertEquals(3, menuOrder(control), "不在 in(...) 内的对照行必须保持不变");
    }

    // ---- fixture helpers ----

    private Long insertDept(String name, int order) {
        jdbc.update("insert into sys_dept(parent_id, dept_name, order_num, status, del_flag, create_by) "
                + "values(0, ?, ?, '0', '0', 'itest')", name, order);
        return jdbc.queryForObject("select dept_id from sys_dept where dept_name = ?", Long.class, name);
    }

    private int deptOrder(Long deptId) {
        return jdbc.queryForObject("select order_num from sys_dept where dept_id = ?", Integer.class, deptId);
    }

    private static SysDept dept(Long id, int order) {
        SysDept d = new SysDept();
        d.setDeptId(id);
        d.setOrderNum(order);
        return d;
    }

    private Long insertMenu(String name, int order) {
        jdbc.update(
                "insert into sys_menu(menu_name, parent_id, order_num, menu_type, visible, status, perms, icon, create_by) "
                        + "values(?, 0, ?, 'C', '0', '0', '', '#', 'itest')",
                name, order);
        return jdbc.queryForObject("select menu_id from sys_menu where menu_name = ?", Long.class, name);
    }

    private int menuOrder(Long menuId) {
        return jdbc.queryForObject("select order_num from sys_menu where menu_id = ?", Integer.class, menuId);
    }

    private static SysMenu menu(Long id, int order) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setOrderNum(order);
        return m;
    }
}
