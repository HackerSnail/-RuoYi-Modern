package com.ruoyi.generator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.generator.config.GenConfig;
import com.ruoyi.generator.domain.GenTable;

/**
 * {@link GenUtils} 实例化改造后的行为测试。
 *
 * <p>
 * 本次重构把 {@code GenUtils} 从"读全局静态配置"改为"注入 {@link GenConfig} 实例"。本测试直接手工装配
 * {@code GenConfig} 并 new 出 {@code GenUtils}，断言 {@code initTable}/{@code convertClassName} 产出的
 * 包名/模块名/业务名/作者/类名/功能名全部来自注入的配置——无需 Spring 容器、零副作用，锁死"配置显式流入而非隐式全局读"。
 * </p>
 */
public class GenUtilsTest {

    private GenConfig genConfig;

    private GenUtils genUtils;

    @BeforeEach
    void setUp() {
        genConfig = new GenConfig();
        genConfig.setAuthor("someone");
        genConfig.setPackageName("com.ruoyi.system");
        genConfig.setAutoRemovePre(true);
        genConfig.setTablePrefix("sys_");
        genUtils = new GenUtils(genConfig);
    }

    @Test
    @DisplayName("initTable：全部生成元数据来自注入配置，autoRemovePre 生效剔除表前缀")
    void initTableUsesInjectedConfig() {
        GenTable table = new GenTable();
        table.setTableName("sys_user");
        table.setTableComment("用户表");

        genUtils.initTable(table, "admin");

        assertEquals("com.ruoyi.system", table.getPackageName());
        assertEquals("system", table.getModuleName());
        assertEquals("user", table.getBusinessName());
        assertEquals("someone", table.getFunctionAuthor());
        // autoRemovePre=true + tablePrefix=sys_ → 剔除 sys_ 得 user → 驼峰 User
        assertEquals("User", table.getClassName());
        // replaceText 去除噪声词“表”
        assertEquals("用户", table.getFunctionName());
        assertEquals("admin", table.getCreateBy());
    }

    @Test
    @DisplayName("autoRemovePre=false 时保留表前缀参与类名")
    void convertClassNameKeepsPrefixWhenDisabled() {
        genConfig.setAutoRemovePre(false);

        // 不去前缀：sys_user → 驼峰 SysUser
        assertEquals("SysUser", genUtils.convertClassName("sys_user"));
    }
}
