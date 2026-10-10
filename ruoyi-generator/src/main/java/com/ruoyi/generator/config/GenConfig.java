package com.ruoyi.generator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 读取代码生成相关配置。
 *
 * <p>
 * 纯实例配置 Bean：由 {@code application.yml} 的 {@code gen.*} 经
 * {@link ConfigurationProperties}
 * 绑定到实例字段，消费方（{@code GenUtils}/{@code GenController}）
 * 通过依赖注入获取，不再有 {@code public static} 全局可变状态，也不再靠 {@code @Value} 写静态字段。
 * 可测试、可隔离、可按环境覆盖。
 * </p>
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "gen")
public class GenConfig {
    /** 作者 */
    private String author;

    /** 生成包路径 */
    private String packageName;

    /** 自动去除表前缀 */
    private boolean autoRemovePre;

    /** 表前缀 */
    private String tablePrefix;

    /** 是否允许生成文件覆盖到本地（自定义路径） */
    private boolean allowOverwrite;

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public boolean isAutoRemovePre() {
        return autoRemovePre;
    }

    public void setAutoRemovePre(boolean autoRemovePre) {
        this.autoRemovePre = autoRemovePre;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public boolean isAllowOverwrite() {
        return allowOverwrite;
    }

    public void setAllowOverwrite(boolean allowOverwrite) {
        this.allowOverwrite = allowOverwrite;
    }
}
