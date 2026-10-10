package com.ruoyi.generator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.generator.config.GenConfig;
import com.ruoyi.generator.service.IGenTableColumnService;
import com.ruoyi.generator.service.IGenTableService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link GenController} 本轮加固行为的单元测试。
 *
 * <p>
 * 覆盖：① {@code importTableSave} 空 tables 守卫（不应触达按名查询，避免 {@code in ()} SQL 语法错误）；
 * ② {@code batchGenCode} 空 tables 抛业务异常；③ {@code createTableSave} 建表前防覆盖（已存在则拒绝、绝不执行 DDL）；
 * ④ {@code createTableSave} 建表成功但导入失败的"半完成"不一致态——失败信息须带回已建表名，便于运维回滚。
 * 全程用 Mockito 隔离 service 与 SecurityUtils 静态上下文，无需 Spring 容器、零副作用。
 * </p>
 */
public class GenControllerTest {

    private final IGenTableService genTableService = mock(IGenTableService.class);

    private final IGenTableColumnService genTableColumnService = mock(IGenTableColumnService.class);

    private final GenConfig genConfig = new GenConfig();

    private GenController controller() {
        return new GenController(genTableService, genTableColumnService, genConfig);
    }

    @Test
    @DisplayName("importTableSave：tables 为空应直接返回错误且不触达按名查询")
    void importTableSaveRejectsEmptyTables() {
        AjaxResult res = controller().importTableSave("", "tpl");

        assertEquals(HttpStatus.ERROR, res.get(AjaxResult.CODE_TAG));
        // 空数组直接短路，绝不能把 new String[0] 传进 selectDbTableListByNames（会拼成 in () 报 SQL 语法错误）
        verify(genTableService, never()).selectDbTableListByNames(any());
        verify(genTableService, never()).importGenTable(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("batchGenCode：tables 为空应抛业务异常而非产出空 zip")
    void batchGenCodeRejectsEmptyTables() {
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThrows(ServiceException.class, () -> controller().batchGenCode(response, ""));
        verify(genTableService, never()).downloadCode(any(String[].class));
    }

    @Test
    @DisplayName("createTableSave：目标表已存在则拒绝覆盖，绝不执行 DDL")
    void createTableSaveRejectsExistingTable() {
        when(genTableService.checkTableExist(anyString())).thenReturn(true);

        AjaxResult res = controller().createTableSave("create table test_gen (id bigint, name varchar(20))", "tpl");

        assertEquals(HttpStatus.ERROR, res.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(res.get(AjaxResult.MSG_TAG)).contains("已存在"));
        // 防覆盖前置于建表：一旦判存在，createTable 与后续导入都不得发生
        verify(genTableService, never()).createTable(anyString());
        verify(genTableService, never()).importGenTable(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("createTableSave：建表成功但导入失败须带回已建表名（暴露半完成态）")
    void createTableSaveImportFailureCarriesCreatedTables() {
        when(genTableService.checkTableExist(anyString())).thenReturn(false);
        when(genTableService.createTable(anyString())).thenReturn(true);
        when(genTableService.selectDbTableListByNames(any())).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("boom")).when(genTableService).importGenTable(anyList(), anyString(), anyString());

        AjaxResult res;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUsername).thenReturn("admin");
            res = controller().createTableSave("create table test_gen (id bigint, name varchar(20))", "tpl");
        }

        assertEquals(HttpStatus.ERROR, res.get(AjaxResult.CODE_TAG));
        String msg = String.valueOf(res.get(AjaxResult.MSG_TAG));
        // 关键：不一致态必须显式告知"表已建、配置未入库"，且带出成功建表名单
        assertTrue(msg.contains("表已建成功"), "失败信息应表明已进入导入阶段失败，实际：" + msg);
        assertTrue(msg.contains("导入生成配置失败"), "失败信息应指明导入失败，实际：" + msg);
        assertTrue(msg.contains("test_gen"), "失败信息应带回已建表名，实际：" + msg);
        // DDL 阶段确已执行
        verify(genTableService).createTable(anyString());
    }

    @Test
    @DisplayName("createTableSave：建表与导入均成功返回 200")
    void createTableSaveHappyPath() {
        when(genTableService.checkTableExist(anyString())).thenReturn(false);
        when(genTableService.createTable(anyString())).thenReturn(true);
        when(genTableService.selectDbTableListByNames(any())).thenReturn(Collections.emptyList());

        AjaxResult res;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUsername).thenReturn("admin");
            res = controller().createTableSave("create table test_gen (id bigint, name varchar(20))", "tpl");
        }

        assertEquals(HttpStatus.SUCCESS, res.get(AjaxResult.CODE_TAG));
        verify(genTableService).createTable(anyString());
        verify(genTableService).importGenTable(anyList(), anyString(), anyString());
    }
}
