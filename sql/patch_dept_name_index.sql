-- 加速部门名同级查重（checkDeptNameUnique: where dept_name=? and parent_id=? and del_flag='0'）
-- 说明：sys_dept 走 del_flag 软删（回收站 del_flag='2'），同一 (dept_name,parent_id) 会因“删除后重建”
-- 出现仅 del_flag 不同的多行；MySQL 5.7 无条件/部分索引，故不能对 (dept_name,parent_id) 加硬 UNIQUE
-- （会拒绝合法的“删除同名后重建”，也会因历史多条软删行冲突）。唯一性由应用层 checkDeptNameUnique(仅查 del_flag='0') 保证，
-- 此处只做非唯一复合索引提速查询。
-- 执行前建议核当前无重名活跃兄弟：SELECT dept_name,parent_id,COUNT(*) FROM sys_dept WHERE del_flag='0' GROUP BY dept_name,parent_id HAVING COUNT(*)>1;
ALTER TABLE sys_dept ADD INDEX idx_dept_name_parent (dept_name, parent_id, del_flag);
