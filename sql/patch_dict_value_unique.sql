-- 字典数据 (dict_type, dict_value) 唯一性 DB 层兜底（防应用层"先查后插"TOCTOU 并发写入重复键值）
-- 执行前须确认无重复：
--   SELECT dict_type, dict_value, COUNT(*) FROM sys_dict_data GROUP BY dict_type, dict_value HAVING COUNT(*) > 1;
-- 本表为物理删除（无 del_flag 软删列），且应用层 checkDictValueUnique 的 SQL 已按 dict_type AND dict_value 联合查重、
-- 不过滤 status，故直接加硬 UNIQUE 与应用语义一致、不会误拒合法写入。
ALTER TABLE sys_dict_data ADD UNIQUE INDEX uk_dict_type_value (dict_type, dict_value);
