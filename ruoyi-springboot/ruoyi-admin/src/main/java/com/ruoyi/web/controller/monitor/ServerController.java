package com.ruoyi.web.controller.monitor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.framework.web.service.ServerMonitorService;

/**
 * 服务器监控
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/monitor/server")
public class ServerController {
    private final ServerMonitorService serverMonitorService;

    public ServerController(ServerMonitorService serverMonitorService) {
        this.serverMonitorService = serverMonitorService;
    }

    /**
     * 获取服务器监控信息：直接返回后台定时预采集的最近快照（不阻塞、不在请求线程内 sleep）。
     */
    @PreAuthorize("@ss.hasPermi('monitor:server:list')")
    @GetMapping()
    public AjaxResult getInfo() {
        return AjaxResult.success(serverMonitorService.getServer());
    }
}
