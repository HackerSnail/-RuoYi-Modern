package com.ruoyi.quartz.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.quartz.domain.SysJob;

/**
 * JobInvokeUtil.invokeMethod 执行点白名单纵深防御测试（RCE 落地点）。
 *
 * <p>
 * 锁定本轮新增的安全 guard：全类名写法在 Class.forName 之前必须命中 JOB_WHITELIST_STR 前缀，否则抛
 * IllegalArgumentException。用例均无需 Spring、也不真正加载被拒类（断言异常先于反射实例化抛出）。
 * </p>
 */
public class JobInvokeUtilTest {

    @Test
    @DisplayName("非白名单全类名：执行点即抛 IllegalArgumentException，先于 Class.forName")
    void rejectsNonWhitelistFullyQualifiedName() {
        SysJob job = new SysJob();
        // beanName=java.lang.Runtime（>1 个点→走全类名分支），不在白名单包前缀内
        job.setInvokeTarget("java.lang.Runtime.getRuntime()");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JobInvokeUtil.invokeMethod(job));
        assertTrue(ex.getMessage().contains("白名单"), "应给出明确的白名单拒绝信息");
    }

    @Test
    @DisplayName("全类名仅“包含”白名单而非“以…开头”：仍被拒（锁死前缀语义，非包含匹配）")
    void rejectsFqnThatOnlyContainsWhitelistNotStartsWith() {
        SysJob job = new SysJob();
        // evil.com.ruoyi.quartz.task.Foo 含白名单子串但不以其开头 → 必须拒（纠偏 review 的“包含即可绕过”前提）
        job.setInvokeTarget("evil.com.ruoyi.quartz.task.Foo.bar()");

        assertThrows(IllegalArgumentException.class, () -> JobInvokeUtil.invokeMethod(job));
    }

    @Test
    @DisplayName("isValidClassName 路由：全类名判 true、bean 名判 false")
    void isValidClassNameRouting() {
        assertTrue(JobInvokeUtil.isValidClassName("com.ruoyi.quartz.task.Foo"));
        assertFalse(JobInvokeUtil.isValidClassName("ryTask"));
    }

    @Test
    @DisplayName("边界：伪同级包 com.ruoyi.quartz.taskevil 在执行点被拒（与 whiteList 同用 isInTaskPackage）")
    void rejectsSiblingPackageBoundaryFqn() {
        SysJob job = new SysJob();
        job.setInvokeTarget("com.ruoyi.quartz.taskevil.Foo.bar()");
        // beanName=com.ruoyi.quartz.taskevil.Foo 不以 "com.ruoyi.quartz.task." 开头 → guard 拒，先于 Class.forName
        assertThrows(IllegalArgumentException.class, () -> JobInvokeUtil.invokeMethod(job));
    }
}
