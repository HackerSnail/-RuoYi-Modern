package com.ruoyi.generator.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.constant.Constants;

/**
 * {@link VelocityInitializer} 配置正确性冒烟测试。
 *
 * <p>
 * 用与启动期完全相同的 {@code initVelocity()} 装配全局引擎后，真实从 classpath 取一个 .vm 模板并 merge，
 * 锁死"loader 配置（resource.loaders=class + classpath 加载器）+ input.encoding"能在
 * Velocity 2.3 下加载并渲染，
 * 补齐此前"全仓无任何测试真正跑过 Velocity 渲染路径"的空白——任何 loader 配置回归都会在此暴露。
 * </p>
 */
public class VelocityInitializerTest {

    @Test
    @DisplayName("initVelocity 后 classpath 模板可被加载并渲染，输出非空且不抛异常")
    void classpathTemplateLoadsAndRenders() {
        VelocityInitializer.initVelocity();

        // getTemplate 会真正解析资源：配置错位将在此抛 ResourceNotFound/ParseError
        Template tpl = Velocity.getTemplate("vm/java/domain.java.vm", Constants.UTF8);
        assertNotNull(tpl, "domain.java.vm 应能被 class 资源加载器从 classpath 取到");

        VelocityContext ctx = new VelocityContext();
        ctx.put("packageName", "com.ruoyi.demo");
        ctx.put("functionName", "演示");
        ctx.put("ClassName", "Demo");
        StringWriter sw = new StringWriter();
        tpl.merge(ctx, sw);

        String out = sw.toString();
        assertTrue(out.contains("package com.ruoyi.demo.domain;"), "已注入的 ${packageName} 应被替换进输出");
        // 模板首部的这段 import 是无条件字面量，出现即证明 classpath 模板被完整读取并 merge
        assertTrue(out.contains("org.apache.commons.lang3.builder.ToStringBuilder"), "输出应含模板内固定 import 行");
    }
}
