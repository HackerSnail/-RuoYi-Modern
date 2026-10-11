package com.ruoyi.common.enums;

/**
 * 限流类型
 *
 * @author HackerSnail <2306002088@qq.com>
 */

public enum LimitType {
    /**
     * 默认策略全局限流
     */
    DEFAULT,

    /**
     * 根据请求者IP进行限流
     */
    IP
}
