package com.ruoyi.framework.manager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.Threads;
import jakarta.annotation.PreDestroy;

/**
 * 异步任务管理器
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public class AsyncManager {
    private static final Logger log = LoggerFactory.getLogger("sys-user");

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
     * 优雅停机：本 bean 构造依赖 scheduledExecutorService，销毁顺序上必然先于该池 bean，
     * 故此处池尚未被 Spring 推断式 shutdown()，走 shutdown + awaitTermination 等待在途
     * 异步日志(登录/操作日志的 DB 插入)跑完——这是池线程为 daemon 时唯一能防止 JVM 提前
     * 退出把它们拦腰砍断的一步（裸 shutdown() 只发起、不等待）。
     */
    @PreDestroy
    public void shutdown() {
        log.info("====关闭后台任务线程池====");
        Threads.shutdownAndAwaitTermination(executor);
    }
}
