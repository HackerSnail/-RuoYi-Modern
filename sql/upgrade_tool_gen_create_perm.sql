-- ----------------------------------------------------------------------------
-- 存量库升级脚本：补齐"代码生成-创建表"细粒度权限点 tool:gen:create
--
-- 背景：GenController.createTableSave 的 @PreAuthorize 已由 hasRole('admin') 改为
--       hasPermi('tool:gen:create')。全新初始化的库（ry_20260417.sql）已内置 menu_id 1063；
--       本脚本用于对"已部署的存量库"补上这条 sys_menu，无需重建库。
--
-- 幂等性：以 perms='tool:gen:create' 作为存在性判据（而非写死主键），重复执行只生效一次；
--         select 均为字面量常量 + from dual，不会触发派生表同名列冲突。
-- 生效范围：超级管理员角色走 *:*:* 通配，插入后即可用；普通角色如需授权，见文件末尾可选项。
-- ----------------------------------------------------------------------------

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
        is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by,
        update_time, remark)
select '1063', '生成建表', '116', '7', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:create', '#',
        'admin', sysdate(), '', null, '创建表结构（建表）按钮权限'
from dual
where not exists (select 1 from sys_menu where perms = 'tool:gen:create');

-- ----------------------------------------------------------------------------
-- 可选：把该按钮权限授予某个已有角色（按需替换 role_id 后取消注释执行）。
-- 超级管理员无需此步（*:*:* 已覆盖）；刻意不默认授予普通角色，避免把原 admin-only 门槛放宽。
-- ----------------------------------------------------------------------------
-- insert into sys_role_menu (role_id, menu_id)
-- select '2', '1063' from dual
-- where not exists (select 1 from sys_role_menu where role_id = '2' and menu_id = '1063');
