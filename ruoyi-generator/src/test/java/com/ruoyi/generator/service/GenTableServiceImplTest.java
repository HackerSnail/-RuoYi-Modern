package com.ruoyi.generator.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.constant.GenConstants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.generator.domain.GenTable;
import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.mapper.GenTableColumnMapper;
import com.ruoyi.generator.mapper.GenTableMapper;
import com.ruoyi.generator.util.GenUtils;

/**
 * {@link GenTableServiceImpl} 本轮健壮性/越权加固的单元测试：全程 Mockito 隔离 mapper 与 GenUtils，
 * 无需 Spring 容器与真实 Velocity，聚焦"传错/缺失输入应尽早抛业务异常，而非解引用 null 触发 500"。
 */
@DisplayName("GenTableServiceImpl：空表/子表缺失/跨表归属/批量上限 守卫")
public class GenTableServiceImplTest {

    private final GenTableMapper genTableMapper = mock(GenTableMapper.class);

    private final GenTableColumnMapper genTableColumnMapper = mock(GenTableColumnMapper.class);

    private final GenUtils genUtils = mock(GenUtils.class);

    private final GenTableServiceImpl service = new GenTableServiceImpl(genTableMapper, genTableColumnMapper,
            genUtils);

    @Test
    @DisplayName("selectGenTableById：ID 不存在即抛，不再对 null 解引用")
    void selectGenTableByIdRejectsMissing() {
        when(genTableMapper.selectGenTableById(anyLong())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.selectGenTableById(999L));
    }

    @Test
    @DisplayName("generatorCode(自定义路径)：未导入表名即抛，不进入渲染/写盘")
    void generatorCodeRejectsMissingTable() {
        when(genTableMapper.selectGenTableByName(anyString())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.generatorCode("no_such_table"));
    }

    @Test
    @DisplayName("previewCode：ID 不存在即抛")
    void previewCodeRejectsMissing() {
        when(genTableMapper.selectGenTableById(anyLong())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.previewCode(1L));
    }

    @Test
    @DisplayName("synchDb：未导入表名即抛，不触及列 diff")
    void synchDbRejectsMissingTable() {
        when(genTableMapper.selectGenTableByName(anyString())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.synchDb("no_such_table"));
    }

    @Test
    @DisplayName("setSubTable：子表名指向未导入表即抛，杜绝下游 getSubTable() NPE")
    void setSubTableRejectsMissingSubTable() {
        GenTable table = new GenTable();
        table.setSubTableName("sub_not_imported");
        when(genTableMapper.selectGenTableByName("sub_not_imported")).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.setSubTable(table));
    }

    @Test
    @DisplayName("setPkColumn：无任何列即抛，不触发 get(0) 越界")
    void setPkColumnRejectsEmptyColumns() {
        GenTable table = new GenTable();
        table.setTableName("t_empty");
        table.setColumns(new ArrayList<>());
        assertThrows(ServiceException.class, () -> service.setPkColumn(table));
    }

    @Test
    @DisplayName("setPkColumn：主子表模板下子表缺失即抛")
    void setPkColumnRejectsMissingSubTable() {
        GenTable table = new GenTable();
        table.setTableName("t_sub");
        table.setTplCategory(GenConstants.TPL_SUB);
        table.setSubTableName("sub_x");
        List<GenTableColumn> cols = new ArrayList<>();
        GenTableColumn c = new GenTableColumn();
        c.setColumnName("id");
        cols.add(c);
        table.setColumns(cols);
        table.setSubTable(null);
        assertThrows(ServiceException.class, () -> service.setPkColumn(table));
    }

    @Test
    @DisplayName("updateGenTable：混入他表 columnId 即拒绝跨表改写")
    void updateGenTableRejectsForeignColumn() {
        GenTable genTable = new GenTable();
        genTable.setTableId(1L);
        GenTableColumn foreign = new GenTableColumn();
        foreign.setColumnId(200L);
        foreign.setTableId(2L); // 属于另一张表
        foreign.setColumnName("col");
        List<GenTableColumn> cols = new ArrayList<>();
        cols.add(foreign);
        genTable.setColumns(cols);
        when(genTableMapper.updateGenTable(any(GenTable.class))).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.updateGenTable(genTable));
        verify(genTableColumnMapper, never()).updateGenTableColumn(any(GenTableColumn.class));
    }

    @Test
    @DisplayName("updateGenTable：columns 为 null（前端只回主表）安全跳过，不 NPE")
    void updateGenTableHandlesNullColumns() {
        GenTable genTable = new GenTable();
        genTable.setTableId(1L);
        // 不设置 columns → getColumns() 返回 null
        when(genTableMapper.updateGenTable(any(GenTable.class))).thenReturn(1);
        assertDoesNotThrow(() -> service.updateGenTable(genTable));
        verify(genTableColumnMapper, never()).updateGenTableColumn(any(GenTableColumn.class));
    }

    @Test
    @DisplayName("downloadCode(String[])：超过批量上限即拒绝，全量下载前先拦")
    void downloadCodeRejectsOversizedBatch() {
        String[] tooMany = new String[51];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = "t" + i;
        }
        assertThrows(ServiceException.class, () -> service.downloadCode(tooMany));
    }

    @Test
    @DisplayName("downloadCode(String[])：空数组即拒绝")
    void downloadCodeRejectsEmpty() {
        assertThrows(ServiceException.class, () -> service.downloadCode(new String[0]));
    }
}
