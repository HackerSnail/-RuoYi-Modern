package com.ruoyi.quartz.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysLogininforService;

/**
 * 登录日志定期归档清理任务
 * <p>
 * 与 {@link OperLogCleanTask} 对称：登录日志只靠监控页手动"清空"（truncate 全表）不够，
 * 没人点的时候表会一直膨胀。本任务按保留天数删除过期数据，配合 sys_job 的 cron 定期执行。
 * <p>
 * 必须放在 com.ruoyi.quartz.task 包下：Constants.JOB_WHITELIST_STR 只放行该包，
 * 否则 sys_job 保存时会被"目标字符串不在白名单内"拦下。
 * <p>
 * invokeTarget 写法：
 * <ul>
 *   <li>{@code logininforCleanTask.clean()} —— 保留天数取默认 180；</li>
 *   <li>{@code logininforCleanTask.clean('180')} —— 引号内为保留天数（字符串参数）；</li>
 *   <li>{@code logininforCleanTask.clean(180)} —— 不带引号时按整型解析，同样支持。</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component("logininforCleanTask")
public class LogininforCleanTask {
    private static final Logger log = LoggerFactory.getLogger(LogininforCleanTask.class);

    /** 无参入口的默认保留天数 */
    private static final int DEFAULT_RETENTION_DAYS = 180;

    private final ISysLogininforService logininforService;

    public LogininforCleanTask(ISysLogininforService logininforService) {
        this.logininforService = logininforService;
    }

    /**
     * 无参入口：保留天数取默认值
     */
    public void clean() {
        clean(DEFAULT_RETENTION_DAYS);
    }

    /**
     * 字符串参数入口：对应 {@code clean('180')}
     *
     * @param retentionDays 保留天数
     */
    public void clean(String retentionDays) {
        clean(parseDays(retentionDays));
    }

    /**
     * 整型参数入口：真正的执行逻辑在这里，抛出的异常会被 AbstractQuartzJob 捕获并写入 sys_job_log.exception_info。
     *
     * @param retentionDays 保留天数，必须大于 0
     */
    public void clean(Integer retentionDays) {
        if (retentionDays == null || retentionDays <= 0) {
            throw new IllegalArgumentException("登录日志保留天数必须大于 0，当前值：" + retentionDays);
        }
        long start = System.currentTimeMillis();
        int rows = logininforService.cleanLogininforBefore(retentionDays);
        log.info("登录日志定期清理完成：保留 {} 天，共删除 {} 条，耗时 {} ms", retentionDays, rows,
                System.currentTimeMillis() - start);
    }

    private int parseDays(String retentionDays) {
        if (StringUtils.isEmpty(retentionDays)) {
            return DEFAULT_RETENTION_DAYS;
        }
        try {
            return Integer.parseInt(retentionDays.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("登录日志保留天数参数不合法：" + retentionDays);
        }
    }
}
