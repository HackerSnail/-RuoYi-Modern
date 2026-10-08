package com.ruoyi.quartz.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysOperLogService;

/**
 * 操作日志定期清理任务
 * <p>
 * 操作日志是几张日志表里最"胖"的一张（oper_param / json_result / error_msg 三个 2000 字符
 * 长字段），只靠监控页手动"清空"不够：没人点的时候表就一直膨胀。本任务按保留天数
 * 删除过期数据，配合 sys_job 的 cron 定期执行。
 * <p>
 * 必须放在 com.ruoyi.quartz.task 包下：Constants.JOB_WHITELIST_STR 只放行该包，
 * 否则 sys_job 保存时会被"目标字符串不在白名单内"拦下。
 * <p>
 * invokeTarget 写法：
 * <ul>
 *   <li>{@code operLogCleanTask.clean()} —— 保留天数取 ruoyi.operLogRetentionDays；</li>
 *   <li>{@code operLogCleanTask.clean('90')} —— 引号内为保留天数（字符串参数）；</li>
 *   <li>{@code operLogCleanTask.clean(90)} —— 不带引号时按整型解析，同样支持。</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component("operLogCleanTask")
public class OperLogCleanTask {
    private static final Logger log = LoggerFactory.getLogger(OperLogCleanTask.class);

    private final ISysOperLogService operLogService;

    public OperLogCleanTask(ISysOperLogService operLogService) {
        this.operLogService = operLogService;
    }

    /**
     * 无参入口：保留天数取配置值
     */
    public void clean() {
        clean(RuoYiConfig.getOperLogRetentionDays());
    }

    /**
     * 字符串参数入口：对应 {@code clean('90')}
     *
     * @param retentionDays 保留天数
     */
    public void clean(String retentionDays) {
        clean(parseDays(retentionDays));
    }

    /**
     * 整型参数入口：对应 {@code clean(90)}。真正的执行逻辑在这里，
     * 抛出的异常会被 AbstractQuartzJob 捕获并写入 sys_job_log.exception_info。
     *
     * @param retentionDays 保留天数，必须大于 0
     */
    public void clean(Integer retentionDays) {
        if (retentionDays == null || retentionDays <= 0) {
            throw new IllegalArgumentException("操作日志保留天数必须大于 0，当前值：" + retentionDays);
        }
        long start = System.currentTimeMillis();
        int rows = operLogService.cleanOperLogBefore(retentionDays);
        log.info("操作日志定期清理完成：保留 {} 天，共删除 {} 条，耗时 {} ms", retentionDays, rows,
                System.currentTimeMillis() - start);
    }

    private int parseDays(String retentionDays) {
        if (StringUtils.isEmpty(retentionDays)) {
            return RuoYiConfig.getOperLogRetentionDays();
        }
        try {
            return Integer.parseInt(retentionDays.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("操作日志保留天数参数不合法：" + retentionDays);
        }
    }
}
