package com.ruoyi.quartz.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SysJobLog.toString 测试：锁定本轮修正点。
 *
 * <p>
 * 覆盖：① 键名 stopTime 改为 endTime（与字段一致）；② 补上最关键的 invokeTarget；
 * ③ exceptionInfo 多行栈在 toString 中只保留首行概要，栈帧体被截断（防刷屏）。
 * </p>
 */
public class SysJobLogTest {

    @Test
    @DisplayName("toString：键名用 endTime 而非 stopTime，且含 invokeTarget")
    void toStringKeysAndInvokeTarget() {
        SysJobLog log = new SysJobLog();
        log.setJobName("demoJob");
        log.setInvokeTarget("ryTask.ryParams('ry')");
        log.setEndTime(LocalDateTime.now());

        String s = log.toString();
        assertTrue(s.contains("invokeTarget"), "toString 应包含 invokeTarget");
        assertTrue(s.contains("endTime"), "toString 应用 endTime 键名");
        assertFalse(s.contains("stopTime"), "toString 不应再有误标的 stopTime");
    }

    @Test
    @DisplayName("toString：exceptionInfo 多行栈只取首行")
    void toStringTrimsExceptionInfoToFirstLine() {
        SysJobLog log = new SysJobLog();
        log.setExceptionInfo("java.lang.RuntimeException: boom\n\tat com.demo.Foo.bar(Foo.java:1)\n\tat com.demo.Baz.run(Baz.java:2)");

        String s = log.toString();
        assertTrue(s.contains("java.lang.RuntimeException: boom"), "首行概要应保留");
        assertFalse(s.contains("com.demo.Foo.bar"), "栈帧体应被截断");
        assertFalse(s.contains("com.demo.Baz.run"), "栈帧体应被截断");
    }

    @Test
    @DisplayName("toString：exceptionInfo 为 CRLF 时首行去 \\r")
    void toStringHandlesCrNewline() {
        SysJobLog log = new SysJobLog();
        log.setExceptionInfo("line1\r\nline2");

        String s = log.toString();
        assertTrue(s.contains("line1"), "应保留首行 line1");
        assertFalse(s.contains("line2"), "第二行应被截断");
    }
}
