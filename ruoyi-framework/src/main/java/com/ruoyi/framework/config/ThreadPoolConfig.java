package com.ruoyi.framework.config;

import com.ruoyi.common.utils.Threads;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置
 *
 * @author ruoyi
 **/
@Configuration
public class ThreadPoolConfig {
    // 核心线程池大小
    private final int corePoolSize;

    // 最大可创建的线程数
    private final int maxPoolSize;

    // 队列最大长度
    private final int queueCapacity;

    // 线程池维护线程所允许的空闲时间
    private final int keepAliveSeconds;

    /**
     * 参数经构造器注入（带默认值回落），可按环境覆盖，避免写死在类里。
     */
    public ThreadPoolConfig(
            @Value("${ruoyi.thread-pool.core-pool-size:50}") int corePoolSize,
            @Value("${ruoyi.thread-pool.max-pool-size:200}") int maxPoolSize,
            @Value("${ruoyi.thread-pool.queue-capacity:1000}") int queueCapacity,
            @Value("${ruoyi.thread-pool.keep-alive-seconds:300}") int keepAliveSeconds) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
        this.keepAliveSeconds = keepAliveSeconds;
    }

    /**
     * 普通异步任务线程池（供 AsyncManager 等提交一次性任务）
     */
    @Bean(name = "threadPoolTaskExecutor")
    public ThreadPoolTaskExecutor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setMaxPoolSize(maxPoolSize);
        executor.setCorePoolSize(corePoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        // 线程名前缀，便于 jstack/日志一眼定位本池线程
        executor.setThreadNamePrefix("async-pool-");
        // 线程池对拒绝任务(无线程可用)的处理策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：容器关闭时等待在途任务完成，最长等待 30 秒（后续按最长任务耗时再调）。
        // 不调 initialize()——ThreadPoolTaskExecutor 实现 InitializingBean，Spring 创建 bean 时会自动
        // 走 afterPropertiesSet()→initialize()；此处若再显式调用会造成线程池二次初始化(遗留孤儿线程)。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * 执行周期性或定时任务（守护线程，重启时不阻塞 JVM 退出）；
     * 重写 afterExecute 统一打印任务抛出的异常，避免定时任务静默失败
     */
    @Bean(name = "scheduledExecutorService")
    protected ScheduledExecutorService scheduledExecutorService() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(corePoolSize,
                BasicThreadFactory.builder().namingPattern("schedule-pool-%d").daemon(true).build(),
                new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                Threads.printException(r, t);
            }
        };
        // 被取消的任务立即移出队列，避免延迟到期前一直堆积（本仓 AsyncManager 未调用 cancel，属防御性设置）。
        executor.setRemoveOnCancelPolicy(true);
        // 关闭后不再执行已排期的延迟任务、也不再延续周期任务。
        // 注意：本池承载 10ms 延迟的一次性异步日志(登录/操作日志)，executeDelayedTasks=false 会使停机
        // 瞬间在途的少量异步任务被丢弃而非执行完；现有 ShutdownManager 已做优雅关停，此处按评审进一步收紧，如实标注该取舍。
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }
}
