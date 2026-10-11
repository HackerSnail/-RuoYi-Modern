package com.ruoyi.generator.util;

import java.util.Properties;
import org.apache.velocity.app.Velocity;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import com.ruoyi.common.constant.Constants;

/**
 * Velocity 模板引擎启动初始化器。
 *
 * <p>
 * 用 {@link PostConstruct} 在应用启动期把 Velocity 全局单例引擎初始化一次，渲染处
 * （{@code GenTableServiceImpl}）直接 {@code Velocity.getTemplate} 复用，不再各自 init。初始化失败即抛中断启动
 * （生成器为核心功能，不可降级）。
 * </p>
 *
 * <p>
 * 选型说明：本仓沿用 Velocity 全局单例而非 {@code VelocityEngine} 实例 bean——全仓仅此一处
 * {@code Velocity.init}、无第二调用者；启动期由 Spring 单线程串行初始化，{@code @PostConstruct} 完成先于容器就绪，
 * 后续请求对其可见，故不存在并发初始化竞态，无需幂等守卫。若未来引入 {@code VelocityEngine} 实例或多处 init，
 * 应改为实例 bean 注入以获得隔离、可测试与天然线程安全。
 * </p>
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public class VelocityInitializer {

    /**
     * 应用启动时把 Velocity 全局引擎初始化一次；渲染处只需 new VelocityContext，不再各自 init。
     */
    @PostConstruct
    public void initOnStartup() {
        initVelocity();
    }

    /**
     * 初始化 Velocity 全局引擎：配置 classpath 资源加载器与输入字符集后 {@code Velocity.init}。
     *
     * <p>
     * 仅在启动期被 {@link #initOnStartup()} 单线程调用一次，故不设幂等守卫。初始化属启动前置条件，
     * 失败不可降级，直接上抛中断应用启动。
     * </p>
     *
     * @throws RuntimeException 引擎初始化失败时抛出（带明确 message，便于定位为 Velocity 配置问题）
     */
    public static void initVelocity() {
        Properties p = new Properties();
        try {
            // Velocity 2.x 显式配置：仅启用名为 class 的 classpath 资源加载器
            // （2.x 正确 key 形式是复数 resource.loaders 列表 + resource.loader.<name>.class，勿用 1.7 的 resource.loader/<name>.resource.loader.class）
            p.setProperty("resource.loaders", "class");
            p.setProperty("resource.loader.class.class",
                    "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
            // 定义输入字符集，避免模板中文名/注释乱码（Velocity 2.x 已移除 output.encoding，落盘编码由下游 Writer 决定）
            p.setProperty(Velocity.INPUT_ENCODING, Constants.UTF8);
            // 初始化 Velocity 引擎，指定配置 Properties
            Velocity.init(p);
        } catch (Exception e) {
            // 初始化属启动前置条件，失败不可降级：带明确 message 上抛中断应用启动
            throw new RuntimeException("Velocity 模板引擎初始化失败", e);
        }
    }
}
