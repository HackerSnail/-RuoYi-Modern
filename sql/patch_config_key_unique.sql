-- 参数配置键名唯一性 DB 层兜底（防应用层"先查后插"TOCTOU 并发写入重复 config_key）
-- 执行前须确认无重复：SELECT config_key, COUNT(*) FROM sys_config GROUP BY config_key HAVING COUNT(*) > 1;
-- 该表无软删、config_key 本应全局唯一，故直接加普通 UNIQUE 索引（非条件唯一）。
ALTER TABLE sys_config ADD UNIQUE INDEX uk_config_key (config_key);
