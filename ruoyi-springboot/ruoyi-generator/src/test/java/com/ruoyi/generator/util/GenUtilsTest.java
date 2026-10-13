package com.ruoyi.generator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.generator.config.GenConfig;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;

/**
 * {@link GenUtils} 实例化改造后的行为测试。
 *
 * <p>
 * 本次重构把 {@code GenUtils} 从"读全局静态配置"改为"注入 {@link GenConfig} 实例"。本测试直接手工装配
 * {@code GenConfig} 并 new 出 {@code GenUtils}，断言
 * {@code initTable}/{@code convertClassName} 产出的
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

    @Test
    @DisplayName("replaceFirst：字面量剔除前缀，含正则元字符的前缀不再被当正则误匹配")
    void replaceFirstIsLiteral() {
        assertEquals("user", GenUtils.replaceFirst("sys_user", new String[] { "sys_", "gen_" }));
        // 关键：前缀 "sys*" 若按正则(sy 后 s 可重复)会误删成 "*abc"；字面量截取应得 "abc"
        assertEquals("abc", GenUtils.replaceFirst("sys*abc", new String[] { "sys*" }));
        // 无命中前缀则原样返回
        assertEquals("sys_user", GenUtils.replaceFirst("sys_user", new String[] { "gen_" }));
    }

    @Test
    @DisplayName("getColumnLength：合法取数，无括号/非数字/缺右括号均降级 0 不抛异常")
    void getColumnLengthDefensive() {
        assertEquals(255, GenUtils.getColumnLength("varchar(255)").intValue());
        assertEquals(0, GenUtils.getColumnLength("text").intValue()); // 无括号
        assertEquals(0, GenUtils.getColumnLength("decimal(10,2)").intValue()); // 括号内非单一整数→NFE→0
        assertEquals(0, GenUtils.getColumnLength("varchar(255").intValue()); // 缺右括号→substringBetween null→0
    }

    @Test
    @DisplayName("initColumnField：数字类型 javaType 正确推导，精度畸形(非数字)降级 Long 不中断")
    void initColumnFieldNumberDerivation() {
        GenTable table = new GenTable();
        table.setTableId(1L);
        table.setCreateBy("admin");

        GenTableColumn dec = new GenTableColumn();
        dec.setColumnName("amount");
        dec.setColumnType("decimal(10,2)");
        GenUtils.initColumnField(dec, table);
        assertEquals(GenConstants.TYPE_BIGDECIMAL, dec.getJavaType());

        GenTableColumn small = new GenTableColumn();
        small.setColumnName("age");
        small.setColumnType("int(9)");
        GenUtils.initColumnField(small, table);
        assertEquals(GenConstants.TYPE_INTEGER, small.getJavaType());

        GenTableColumn big = new GenTableColumn();
        big.setColumnName("bigcol");
        big.setColumnType("int(11)");
        GenUtils.initColumnField(big, table);
        assertEquals(GenConstants.TYPE_LONG, big.getJavaType());

        // 畸形：int(abc) 解析抛 NFE，应被捕获并降级为 Long，不阻断导入
        GenTableColumn weird = new GenTableColumn();
        weird.setColumnName("weird");
        weird.setColumnType("int(abc)");
        GenUtils.initColumnField(weird, table);
        assertEquals(GenConstants.TYPE_LONG, weird.getJavaType());
    }
}
