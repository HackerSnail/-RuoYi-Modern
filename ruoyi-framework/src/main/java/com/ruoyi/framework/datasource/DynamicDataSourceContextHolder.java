package com.ruoyi.framework.datasource;

import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源切换处理
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class DynamicDataSourceContextHolder {
    public static final Logger log = LoggerFactory.getLogger(DynamicDataSourceContextHolder.class);

    /**
     * 使用ThreadLocal维护数据源栈：ThreadLocal为每个线程提供独立副本，栈(Deque)则支撑
     * @DataSource 嵌套调用——内层切换出栈后自动恢复外层数据源，不会像单值那样被 clear 打回默认库。
     */
    private static final ThreadLocal<Deque<String>> CONTEXT_HOLDER = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 入栈：切换到指定数据源（嵌套时保留外层，出栈后恢复）
     */
    public static void pushDataSourceType(String dsType) {
        log.info("切换到{}数据源", dsType);
        CONTEXT_HOLDER.get().push(dsType);
    }

    /**
     * 出栈：退出当前数据源上下文，回退到外层数据源；栈空时移除 ThreadLocal 防止线程池场景下的内存泄漏
     */
    public static void pollDataSourceType() {
        Deque<String> stack = CONTEXT_HOLDER.get();
        stack.poll();
        if (stack.isEmpty()) {
            CONTEXT_HOLDER.remove();
        }
    }

    /**
     * 获得当前生效的数据源（栈顶）；栈空时返回 null，由路由数据源回退到默认库
     */
    public static String getDataSourceType() {
        return CONTEXT_HOLDER.get().peek();
    }
}
