-- ----------------------------------------------------------------------------
-- 回收站菜单/按钮权限种子（幂等，可重复执行不报错、不产生重复行）
--   配合 SysRecycleBinController 的程序化鉴权：perms 串必须与 RecycleBinType 派生串逐字一致
--     查看位：system:recyclebin:list
--     动作位：system:user:restore / system:user:purge
--             system:role:restore / system:role:purge
--             system:dept:restore / system:dept:purge
--   menu_id 走自增（表 auto_increment=2000），避开既有种子菜单 100-115/1000-1040 段。
--   超管(role_id=1)本身已具 *:*:* 通配（SysPermissionService 对 isAdmin 直接放行），
--   此处关联仅为演示“如何把新权限授予非超管角色”，幂等追加到 sys_role_menu。
-- ----------------------------------------------------------------------------

-- 1) 回收站目录菜单（C 型），挂在“系统管理”(menu_id=1) 下
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '回收站', 1, 10, 'recyclebin', 'system/recyclebin/index', '', '', 1, 0, 'C', '0', '0', 'system:recyclebin:list', 'swagger', 'admin', sysdate(), '回收站菜单'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:recyclebin:list');

-- 2) 六个动作按钮（F 型），父节点取回收站目录菜单 id（perms 唯一→标量子查询单行），逐个以 perms 作幂等键
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '用户恢复', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 1, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:user:restore', '#', 'admin', sysdate(), '回收站-恢复用户'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:user:restore');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '用户彻底删除', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 2, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:user:purge', '#', 'admin', sysdate(), '回收站-彻底删除用户'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:user:purge');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '角色恢复', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 3, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:role:restore', '#', 'admin', sysdate(), '回收站-恢复角色'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:role:restore');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '角色彻底删除', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 4, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:role:purge', '#', 'admin', sysdate(), '回收站-彻底删除角色'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:role:purge');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '部门恢复', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 5, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:dept:restore', '#', 'admin', sysdate(), '回收站-恢复部门'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:dept:restore');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '部门彻底删除', (SELECT menu_id FROM sys_menu WHERE perms = 'system:recyclebin:list'), 6, '', NULL, '', '', 1, 0, 'F', '0', '0', 'system:dept:purge', '#', 'admin', sysdate(), '回收站-彻底删除部门'
FROM dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:dept:purge');

-- 3) 关联到超管角色（role_id=1），幂等；(role_id,menu_id) 为主键，重复执行不报错
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE m.perms IN (
    'system:recyclebin:list',
    'system:user:restore', 'system:user:purge',
    'system:role:restore', 'system:role:purge',
    'system:dept:restore', 'system:dept:purge'
)
AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);
