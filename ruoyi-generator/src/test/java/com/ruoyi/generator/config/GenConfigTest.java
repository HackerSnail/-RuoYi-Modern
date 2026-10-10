package com.ruoyi.generator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.ruoyi.generator.util.GenUtils;

/**
 * {@link GenConfig} 配置绑定测试。
 *
 * <p>
 * 本次重构把配置来源从"generator.yml 被 DefaultPropertySourceFactory 当 properties 误解析、再靠 @Value 读压平后的顶层键"
 * 改为标准 {@code @ConfigurationProperties(prefix=gen)} 绑定 application.yml 的 {@code gen.*}。
 * 本测试用 {@link ApplicationContextRunner} 以与 application.yml 完全一致的 camelCase 键名喂属性，断言六个字段
 * 精确绑定到实例——锁死前缀/camelCase 键这条最易出错的绑定契约，防止回归成 null。
 * </p>
 */
public class GenConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(GenConfig.class)
    @Import(GenUtils.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("gen.* 的 camelCase 键精确绑定到实例字段，且 GenUtils 可注入配置 bean")
    void bindsGenProperties() {
        runner.withPropertyValues(
                "gen.author=ruoyi",
                "gen.packageName=com.ruoyi.system",
                "gen.autoRemovePre=false",
                "gen.tablePrefix=sys_",
                "gen.allowOverwrite=false")
                .run(context -> {
                    GenConfig cfg = context.getBean(GenConfig.class);
                    assertThat(cfg.getAuthor()).isEqualTo("ruoyi");
                    assertThat(cfg.getPackageName()).isEqualTo("com.ruoyi.system");
                    assertThat(cfg.isAutoRemovePre()).isFalse();
                    assertThat(cfg.getTablePrefix()).isEqualTo("sys_");
                    assertThat(cfg.isAllowOverwrite()).isFalse();
                    // 配置 bean 能被实例化的 GenUtils 依赖注入，佐证装配链路完整
                    assertThat(context.getBean(GenUtils.class)).isNotNull();
                });
    }
}
