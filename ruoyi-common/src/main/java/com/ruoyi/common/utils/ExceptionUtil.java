package com.ruoyi.common.utils;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 错误信息处理类。
 *
 * @author ruoyi
 */
public class ExceptionUtil {
    /**
     * 获取exception的详细错误信息。
     */
    public static String getExceptionMessage(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw, true));
        return sw.toString();
    }

    public static String getRootErrorMessage(Exception e) {
        // 纯 JDK 沿 cause 链走到最后一个非 null 的 cause（即根因）；e 无 cause 时以 e 自身兜底。
        Throwable root = e;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        if (root == null) {
            return "";
        }
        String msg = root.getMessage();
        // msg 为 null 统一展示为 "null"（无需再走 StringUtils.defaultString）
        return msg == null ? "null" : msg;
    }
}
