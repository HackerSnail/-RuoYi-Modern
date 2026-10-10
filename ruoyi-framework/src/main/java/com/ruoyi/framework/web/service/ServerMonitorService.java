package com.ruoyi.framework.web.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.framework.web.domain.Server;

/**
 * 服务器监控采集服务：后台定时预采集，请求直接读最近快照，避免 {@link Server#copyTo()} 内为算 CPU 使用率
 * 而 sleep 1 秒阻塞 Web 线程。
 *
 * <p>
 * 线程安全：每次刷新 new 一个全新的 {@link Server} 采集完成后，再经 {@code volatile} 引用原子替换。
 * 读方拿到的永远是"已完整构造、之后不再被改写"的快照，不存在读到"改一半"的问题（对比原地改写共享对象的写法）。
 * </p>
 *
 * @author ruoyi
 */
@Component
public class ServerMonitorService {
    private static final Logger log = LoggerFactory.getLogger(ServerMonitorService.class);

    /** 后台刷新间隔（秒）：请求不触发采集，最多读到 5 秒前的快照 */
    private static final long REFRESH_INTERVAL_SECONDS = 5L;

    /** 最近一次采集完成的快照；初始为空壳，避免刷新完成前返回 null */
    private volatile Server latest = new Server();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-monitor-refresh");
            t.setDaemon(true);
            return t;
        });
        // initialDelay=0：启动即采一次（1 秒采集在守护线程内进行，不阻塞 Web）
        scheduler.scheduleAtFixedRate(this::refresh, 0, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 采集一份新快照并原子替换 {@link #latest}。采集异常只记日志、保留上一份快照，不外抛。
     */
    private void refresh() {
        try {
            Server snapshot = new Server();
            snapshot.copyTo();
            this.latest = snapshot;
        } catch (Exception e) {
            log.error("服务器监控信息后台采集失败，沿用上一次快照", e);
        }
    }

    /**
     * 返回最近一次采集完成的服务器快照（不阻塞、不触发采集）。
     */
    public Server getServer() {
        return latest;
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
