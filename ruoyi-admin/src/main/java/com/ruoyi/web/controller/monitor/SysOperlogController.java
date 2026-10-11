package com.ruoyi.web.controller.monitor;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.service.ISysOperLogService;

/**
 * 操作日志记录
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/monitor/operlog")
public class SysOperlogController extends BaseController {
    /**
     * 清空操作日志的二次确认口令：前端弹窗要求用户确认后随请求回传，后端强校验后才执行。
     * <p>
     * 风险不对等：批量删除只影响勾选的若干条，清空是全表删除且不可恢复。故清空已从
     * remove 拆出为独立权限 monitor:operlog:clean（需单独授权），只靠前端弹窗拦不住
     * 脚本误调、请求重试、抓包重放，所以服务端必须再校验一次二次确认口令。
     */
    private static final String CLEAN_CONFIRM_TOKEN = "CLEAN_ALL_OPERLOG";

    private final ISysOperLogService operLogService;

    public SysOperlogController(ISysOperLogService operLogService) {
        this.operLogService = operLogService;
    }

    /**
     * 获取操作日志列表
     */
    @PreAuthorize("@ss.hasPermi('monitor:operlog:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysOperLog operLog) {
        startPage();
        List<SysOperLog> list = operLogService.selectOperLogList(operLog);
        return getDataTable(list);
    }

    /**
     * 导出操作日志
     */
    @Log(title = "操作日志", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('monitor:operlog:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysOperLog operLog) {
        List<SysOperLog> list = exportLimit(() -> operLogService.selectOperLogList(operLog));
        ExcelUtil<SysOperLog> util = new ExcelUtil<>(SysOperLog.class);
        util.exportExcel(response, list, "操作日志");
    }

    /**
     * 批量删除操作日志
     */
    @Log(title = "操作日志", businessType = BusinessType.DELETE)
    @PreAuthorize("@ss.hasPermi('monitor:operlog:remove')")
    @DeleteMapping("/{operIds}")
    public AjaxResult remove(@PathVariable Long[] operIds) {
        return toAjax(operLogService.deleteOperLogByIds(operIds));
    }

    /**
     * 清空操作日志（高危，全表删除且不可恢复）
     *
     * @param confirm 前端二次确认口令，必须等于 {@value #CLEAN_CONFIRM_TOKEN}，否则直接拒绝
     */
    @Log(title = "操作日志", businessType = BusinessType.CLEAN)
    @PreAuthorize("@ss.hasPermi('monitor:operlog:clean')")
    @DeleteMapping("/clean")
    public AjaxResult clean(@RequestParam(name = "confirm", required = false, defaultValue = "") String confirm) {
        if (!CLEAN_CONFIRM_TOKEN.equals(confirm)) {
            return AjaxResult.error("清空操作日志需要二次确认，已拒绝执行");
        }
        int rows = operLogService.cleanOperLog();
        // @Log(BusinessType.CLEAN) 会把返回的 AjaxResult 记入 sys_oper_log，
        // 把条数放进 msg，审计时"谁在什么时候清空了多少条"三个要素就齐了
        logger.warn("【高危】用户[{}]清空了全部操作日志，共 {} 条", getUsername(), rows);
        return success("已清空 " + rows + " 条操作日志");
    }
}
