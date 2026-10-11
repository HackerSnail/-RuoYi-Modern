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
import com.ruoyi.framework.web.service.SysPasswordService;
import com.ruoyi.system.domain.SysLogininfor;
import com.ruoyi.system.service.ISysLogininforService;

/**
 * 系统访问记录
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/monitor/logininfor")
public class SysLogininforController extends BaseController {
    /**
     * 清空登录日志的二次确认口令：与操作日志同理——清空和批量删除共用 remove 权限，
     * 但清空是全表删除且不可恢复，必须在服务端再校验一次确认口令。
     */
    private static final String CLEAN_CONFIRM_TOKEN = "CLEAN_ALL_LOGININFOR";

    private final ISysLogininforService logininforService;

    private final SysPasswordService passwordService;

    public SysLogininforController(ISysLogininforService logininforService, SysPasswordService passwordService) {
        this.logininforService = logininforService;
        this.passwordService = passwordService;
    }

    /**
     * 获取登录日志列表
     */
    @PreAuthorize("@ss.hasPermi('monitor:logininfor:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysLogininfor logininfor) {
        startPage();
        List<SysLogininfor> list = logininforService.selectLogininforList(logininfor);
        return getDataTable(list);
    }

    /**
     * 导出登录日志
     */
    @Log(title = "登录日志", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('monitor:logininfor:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysLogininfor logininfor) {
        List<SysLogininfor> list = exportLimit(() -> logininforService.selectLogininforList(logininfor));
        ExcelUtil<SysLogininfor> util = new ExcelUtil<>(SysLogininfor.class);
        util.exportExcel(response, list, "登录日志");
    }

    /**
     * 批量删除登录日志
     */
    @PreAuthorize("@ss.hasPermi('monitor:logininfor:remove')")
    @Log(title = "登录日志", businessType = BusinessType.DELETE)
    @DeleteMapping("/{infoIds}")
    public AjaxResult remove(@PathVariable Long[] infoIds) {
        return toAjax(logininforService.deleteLogininforByIds(infoIds));
    }

    /**
     * 清空登录日志（高危，全表删除且不可恢复）
     *
     * @param confirm 前端二次确认口令，必须等于 {@value #CLEAN_CONFIRM_TOKEN}，否则直接拒绝
     */
    @PreAuthorize("@ss.hasPermi('monitor:logininfor:remove')")
    @Log(title = "登录日志", businessType = BusinessType.CLEAN)
    @DeleteMapping("/clean")
    public AjaxResult clean(@RequestParam(name = "confirm", required = false, defaultValue = "") String confirm) {
        if (!CLEAN_CONFIRM_TOKEN.equals(confirm)) {
            return AjaxResult.error("清空登录日志需要二次确认，已拒绝执行");
        }
        int approxRows = logininforService.cleanLogininfor();
        // 条数取自 information_schema 统计值，为清空前近似快照，随 msg 进入 @Log 的 jsonResult；审计重在“谁/何时”，数字标“约”
        logger.warn("【高危】用户[{}]清空了全部登录日志，约 {} 条（估算）", getUsername(), approxRows);
        return success("已清空登录日志，约 " + approxRows + " 条（估算值）");
    }

    /**
     * 账户解锁：清除密码错误次数缓存，解除因多次输错密码触发的锁定
     */
    @PreAuthorize("@ss.hasPermi('monitor:logininfor:unlock')")
    @Log(title = "账户解锁", businessType = BusinessType.OTHER)
    @GetMapping("/unlock/{userName}")
    public AjaxResult unlock(@PathVariable("userName") String userName) {
        passwordService.clearLoginRecordCache(userName);
        return success();
    }
}
