package com.ruoyi.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解：标注在方法上，方法执行前获取 Redisson 可重入锁，执行后释放。
 *
 * <p>
 * key 支持 SpEL，例如 {@code #userId}、{@code #order.id}，利用编译期 -parameters
 * 反射获取形参名；不含 {@code #} 时按字面量处理。leaseTime 为 -1 时启用 Redisson 看门狗
 * 自动续期（业务未执行完不会因过期而失锁）。
 * </p>
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {
    /** 锁的业务 key，支持 SpEL 表达式 */
    String key();

    /** key 前缀，最终锁名为 prefix:spel(key) */
    String prefix() default "lock";

    /** 获取锁的最长等待时间；0 表示不等待（拿不到立即失败） */
    long waitTime() default 3L;

    /** 锁持有时间（到期自动释放）；-1 表示由看门狗按业务执行情况自动续期 */
    long leaseTime() default -1L;

    /** 时间单位 */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
