package com.ruoyi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ruoyi.generator.domain.GenTableColumn;
import com.ruoyi.generator.mapper.GenTableColumnMapper;

/**
 * {@link GenTableColumnMapper} 本轮改动的集成测试（批量插入 batchInsertGenTableColumn、按主键收窄的
 * deleteGenTableColumns、两个批量删除的空集合守卫、以及多表批量反读列 selectDbTableColumnsByNames），
 * 连真实 MySQL、方法级事务自动回滚。
 *
 * <p>
 * 用唯一哨兵 tableId + create_by='itest' 自建列 fixture，不依赖也不污染共享库既有数据。空集合守卫用例
 * 直接证明修复：改前 {@code delete ... where column_id in ()} 会抛 BadSqlGrammarException，改后应安全返回 0。
 * </p>
 */
@DisplayName("GenTableColumnMapper：批量插入与空集合删除守卫（真实 MySQL，事务回滚）")
public class GenTableColumnMapperIntegrationTest extends BaseMapperIntegrationTest {

    @Autowired
    private GenTableColumnMapper genTableColumnMapper;

    /** 生成本用例独有的哨兵 tableId，避免与共享库既有数据碰撞（结束随事务回滚） */
    private long sentinelTableId() {
        return System.nanoTime();
    }

    private GenTableColumn newColumn(long tableId, String columnName) {
        GenTableColumn column = new GenTableColumn();
        column.setTableId(tableId);
        column.setCreateBy("itest");
        column.setColumnName(columnName);
        column.setJavaField(columnName);
        column.setJavaType("String");
        column.setColumnType("varchar(255)");
        column.setIsPk("0");
        column.setIsInsert("1");
        column.setIsEdit("1");
        column.setIsList("1");
        column.setIsQuery("0");
        column.setQueryType("EQ");
        column.setHtmlType("input");
        column.setSort(1);
        return column;
    }

    @Test
    @DisplayName("批量插入多列并回读；随后按 column_id 精删其一")
    void batchInsertThenReadBackAndDeleteByColumnIds() {
        long tableId = sentinelTableId();
        List<GenTableColumn> columns = new ArrayList<>();
        columns.add(newColumn(tableId, "itest_col_a"));
        columns.add(newColumn(tableId, "itest_col_b"));
        columns.add(newColumn(tableId, "itest_col_c"));

        int affected = genTableColumnMapper.batchInsertGenTableColumn(columns);
        assertEquals(3, affected, "多值 INSERT 应报告写入 3 行");

        List<GenTableColumn> loaded = genTableColumnMapper.selectGenTableColumnListByTableId(tableId);
        assertEquals(3, loaded.size(), "回读应拿到全部三列");
        assertTrue(loaded.stream().allMatch(c -> "itest".equals(c.getCreateBy())),
                "fixture 列应以 create_by='itest' 标记");

        // 取第一列的 column_id 做精确删除
        Long oneColumnId = jdbc.queryForObject(
                "select column_id from gen_table_column where create_by = 'itest' and table_id = ? limit 1",
                Long.class, tableId);
        int deleted = genTableColumnMapper.deleteGenTableColumns(Collections.singletonList(oneColumnId));
        assertEquals(1, deleted, "按单个 column_id 应删除 1 行");
        assertEquals(2, genTableColumnMapper.selectGenTableColumnListByTableId(tableId).size(),
                "删除后应剩 2 列");
    }

    @Test
    @DisplayName("空集合/空数组/null 删除退化为 1=0：不抛语法错、不误删、返回 0")
    void emptyCollectionGuardsDoNotThrow() {
        // 这些调用在加空集合守卫前会生成 `... in ()`，MySQL 直接语法报错；现应安全返回 0
        assertEquals(0, genTableColumnMapper.deleteGenTableColumns(Collections.emptyList()));
        assertEquals(0, genTableColumnMapper.deleteGenTableColumns(null));
        assertEquals(0, genTableColumnMapper.deleteGenTableColumnByIds(new Long[0]));
        assertEquals(0, genTableColumnMapper.deleteGenTableColumnByIds(null));
    }

    @Test
    @DisplayName("批量反读多表列：行数等于逐表单查之和，并按 tableName 正确分组")
    void batchReadMatchesPerTableAndGroups() {
        // 借用共享库中必定存在的两张元数据表作为只读 fixture（information_schema 反读，不写库）
        String[] names = { "gen_table", "gen_table_column" };
        List<GenTableColumn> batch = genTableColumnMapper.selectDbTableColumnsByNames(names);
        List<GenTableColumn> genTableCols = genTableColumnMapper.selectDbTableColumnsByName("gen_table");
        List<GenTableColumn> genColumnCols = genTableColumnMapper.selectDbTableColumnsByName("gen_table_column");

        assertEquals(genTableCols.size() + genColumnCols.size(), batch.size(),
                "批量反读行数应等于两表单查之和");

        // 分组键：批量结果应携非空 tableName（单查结果不 select table_name，故为 null）
        assertTrue(batch.stream().allMatch(c -> c.getTableName() != null),
                "批量结果每列都应携带归属表名分组键");
        Map<String, Long> counts = batch.stream()
                .collect(Collectors.groupingBy(GenTableColumn::getTableName, Collectors.counting()));
        assertEquals((long) genTableCols.size(), counts.getOrDefault("gen_table", 0L), "gen_table 分组行数应一致");
        assertEquals((long) genColumnCols.size(), counts.getOrDefault("gen_table_column", 0L),
                "gen_table_column 分组行数应一致");
    }

    @Test
    @DisplayName("空/null 表名数组批量读退化为 1=0：不抛语法错、返回空")
    void emptyNamesBatchReadReturnsEmpty() {
        // 未加守卫前会生成 `table_name in ()`，MySQL 语法报错；现应退化为 1=0 返回空
        assertTrue(genTableColumnMapper.selectDbTableColumnsByNames(new String[0]).isEmpty(),
                "空数组应返回空且不报错");
        assertTrue(genTableColumnMapper.selectDbTableColumnsByNames(null).isEmpty(),
                "null 应返回空且不报错");
    }
}
