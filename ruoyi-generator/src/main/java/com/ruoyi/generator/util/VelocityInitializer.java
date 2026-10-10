package com.ruoyi.generator.util;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.velocity.app.Velocity;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import com.ruoyi.common.constant.Constants;

/**
 * VelocityEngine工厂
 * 
 * @author ruoyi
 */
@Component
public class VelocityInitializer {
    /** 全局引擎仅需初始化一次的幂等标志，杜绝每次生成/预览都重跑 Velocity.init */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    /**
     * 应用启动时把 Velocity 全局引擎初始化一次；渲染处只需 new VelocityContext，不再各自 init。
     */
    @PostConstruct
    public void initOnStartup() {
        initVelocity();
    }

    /**
     * 初始化 Velocity 模板引擎。
     *
     * <p>
     * 配置 classpath 资源加载器并初始化引擎，供后续渲染 .vm 模板生成代码。全局单例引擎，本方法用
     * {@link #INITIALIZED} 做幂等守卫：多次调用只在首次真正 {@code Velocity.init}，避免重复重建引擎
     * 带来的开销与批量下载时的并发隐患。初始化失败会复位标志以允许下次重试（不半初始化永久短路）。
     * </p>
     *
     * @throws RuntimeException 引擎初始化失败时抛出
     */
    public static void initVelocity() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        Properties p = new Properties();
        try {
            // 加载classpath目录下的vm文件
            p.setProperty("resource.loader.file.class",
                    "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
            // 定义字符集，避免模板中文名/注释乱码
            p.setProperty(Velocity.INPUT_ENCODING, Constants.UTF8);
            // 初始化Velocity引擎，指定配置Properties
            Velocity.init(p);
        } catch (Exception e) {
            // 初始化属启动前置条件，失败不可降级：复位标志允许下次重试，并上抛中断
            INITIALIZED.set(false);
            throw new RuntimeException(e);
        }
    }
}
