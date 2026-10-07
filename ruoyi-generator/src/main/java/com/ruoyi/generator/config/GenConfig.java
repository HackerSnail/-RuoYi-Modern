package com.ruoyi.generator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * 读取代码生成相关配置
 * 
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "gen")
@PropertySource(value = { "classpath:generator.yml" })
public class GenConfig {
    /** 作者 */
    public static String author;

    /** 生成包路径 */
    public static String packageName;

    /** 自动去除表前缀 */
    public static boolean autoRemovePre;

    /** 表前缀 */
    public static String tablePrefix;

    /** 是否允许生成文件覆盖到本地（自定义路径） */
    public static boolean allowOverwrite;

    /**
     * 获取作者名（供模板生成时写入 @author）
     *
     * @return generator.yml 中配置的 author 值
     */
    public static String getAuthor() {
        return author;
    }

    /**
     * 注入作者配置。
     *
     * <p>
     * Spring 的 @ConfigurationProperties 只能写入实例字段，此处通过带 @Value 的实例 setter
     * 将值转存到 static 字段，以便静态工具类（VelocityUtils 等）无需持有 bean 即可读取。
     * </p>
     *
     * @param author generator.yml 中的 author 值
     */
    @Value("${author}")
    public void setAuthor(String author) {
        GenConfig.author = author;
    }

    /**
     * 获取生成包路径（生成代码的 package 声明前缀）
     *
     * @return generator.yml 中配置的 packageName 值
     */
    public static String getPackageName() {
        return packageName;
    }

    /**
     * 注入生成包路径到 static 字段（同上 static 转存设计）
     *
     * @param packageName generator.yml 中的 packageName 值
     */
    @Value("${packageName}")
    public void setPackageName(String packageName) {
        GenConfig.packageName = packageName;
    }

    /**
     * 是否自动去除表前缀（生成类名时是否剔除 tablePrefix）
     *
     * @return true 表示去前缀
     */
    public static boolean getAutoRemovePre() {
        return autoRemovePre;
    }

    /**
     * 注入“自动去除表前缀”开关到 static 字段
     *
     * @param autoRemovePre generator.yml 中的 autoRemovePre 值
     */
    @Value("${autoRemovePre}")
    public void setAutoRemovePre(boolean autoRemovePre) {
        GenConfig.autoRemovePre = autoRemovePre;
    }

    /**
     * 获取表前缀（如 sys_，用于生成时从表名剔除）
     *
     * @return generator.yml 中配置的 tablePrefix 值
     */
    public static String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * 注入表前缀到 static 字段
     *
     * @param tablePrefix generator.yml 中的 tablePrefix 值
     */
    @Value("${tablePrefix}")
    public void setTablePrefix(String tablePrefix) {
        GenConfig.tablePrefix = tablePrefix;
    }

    /**
     * 是否允许生成文件覆盖到本地自定义路径
     *
     * @return true 表示允许覆盖写入磁盘
     */
    public static boolean isAllowOverwrite() {
        return allowOverwrite;
    }

    /**
     * 注入“允许覆盖”开关到 static 字段
     *
     * @param allowOverwrite generator.yml 中的 allowOverwrite 值
     */
    @Value("${allowOverwrite}")
    public void setAllowOverwrite(boolean allowOverwrite) {
        GenConfig.allowOverwrite = allowOverwrite;
    }
}
