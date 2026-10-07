package com.ruoyi.generator.util;

import java.util.Properties;
import org.apache.velocity.app.Velocity;
import com.ruoyi.common.constant.Constants;

/**
 * VelocityEngine工厂
 * 
 * @author ruoyi
 */
public class VelocityInitializer {
    /**
     * 初始化 Velocity 模板引擎。
     *
     * <p>
     * 配置 classpath 资源加载器并初始化引擎，供后续渲染 .vm 模板生成代码。需在首次使用前调用。
     * </p>
     *
     * @throws Exception 引擎初始化失败时抛出，包装为 RuntimeException 上抛
     */
    public static void initVelocity() {
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
            // 初始化属启动前置条件，失败不可降级，直接上抛中断
            throw new RuntimeException(e);
        }
    }
}
