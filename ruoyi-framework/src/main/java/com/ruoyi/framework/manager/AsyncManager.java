package com.ruoyi.framework.manager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.Threads;

/**
 * 异步任务管理器
 *
 * @author ruoyi
 */
@Component
public class AsyncManager {
    /**
     * 操作延迟10毫秒
     */
    private static final int OPERATE_DELAY_TIME = 10;

    /**
     * 异步操作任务调度线程池。由 Spring 构造器注入，取代原先在类加载期用
     * SpringUtils.getBean("scheduledExecutorService") 的全局查 Bean 做法——
     * 消除启动时序脆弱性与静态全局状态，依赖关系显式化。
     */
    private final ScheduledExecutorService executor;

    public AsyncManager(@Qualifier("scheduledExecutorService") ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 执行任务
     *
     * @param task 任务
     */
    public void execute(Runnable task) {
        executor.schedule(task, OPERATE_DELAY_TIME, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止任务线程池：容器关闭时由 ShutdownManager 的 @PreDestroy 调用，
     * 走 shutdown + awaitTermination 等待在途异步任务跑完（比 Spring 推断的裸
     * shutdown() 更完整，后者只发起不等待）。
     */
    public void shutdown() {
        Threads.shutdownAndAwaitTermination(executor);
    }
}
