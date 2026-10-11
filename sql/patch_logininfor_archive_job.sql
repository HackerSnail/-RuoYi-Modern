-- 登录日志定期归档清理任务注册（sys_job）
-- 调用目标 bean：com.ruoyi.quartz.task.LogininforCleanTask（@Component("logininforCleanTask")，位于 JOB_WHITELIST_STR 放行的包）
-- 与「操作日志定期清理」种子对称：cron 每天凌晨4点(错开 oper 的3点)、misfire=3(不立即触发)、concurrent=1(禁止并发)、status=1(默认暂停)。
-- status=1 表示预置但默认暂停，确认保留策略(此处 180 天)后在「定时任务」页面手动启用。
-- 显式不写 job_id，走自增，避免与既有任务主键冲突。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
VALUES ('登录日志定期归档清理', 'SYSTEM', 'logininforCleanTask.clean(''180'')', '0 0 4 * * ?', '3', '1', '1', 'admin', sysdate(),
        '删除 180 天前的 sys_logininfor；默认暂停(status=1)，确认策略后在“定时任务”页面启用');
