-- 操作日志"清空"拆分为独立权限 monitor:operlog:clean（原与按条删除共用 monitor:operlog:remove）
-- 背景：清空是全表 truncate、不可恢复，风险高于按条删除。拆分后清空需单独授权；
--       超级管理员(role_key=admin)走 PermissionService 短路不受影响，非管理员须在"角色"页面显式勾选本按钮。
-- 幂等：菜单 ID 1064 或该 perms 已存在则跳过，可重复执行。
-- 说明：新库初始安装应把本按钮折叠进 ry_20260417.sql 的 sys_menu 种子；此处为已部署库的增量补丁。

-- 1) 在"操作日志"菜单(500)下新增"清空日志"按钮（menu_type=F，order_num=4 排在导出之后）
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
select 1064, '清空日志', 500, 4, '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:clean', '#', 'admin', sysdate(), '', null, '清空全部操作日志(高危,独立权限)'
from dual
where not exists (select 1 from sys_menu where menu_id = 1064 or perms = 'monitor:operlog:clean');

-- 2) 授权给超级管理员角色(role_id=1)，保持其菜单树完整
insert into sys_role_menu (role_id, menu_id)
select 1, 1064
from dual
where not exists (select 1 from sys_role_menu where role_id = 1 and menu_id = 1064);
