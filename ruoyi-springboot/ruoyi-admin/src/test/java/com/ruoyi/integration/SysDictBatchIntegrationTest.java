package com.ruoyi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;

/**
 * 字典批量删除所依赖的两条批量查询的集成测试：{@code selectDictTypeByIds}（按 id 集合回查类型）
 * 与 {@code selectAssignedDictTypes}（在给定类型中筛出已被 sys_dict_data 占用的类型）。
 * 用例自建字典类型 fixture（其中一个挂一条数据以构成"已分配"），方法级事务结束后自动回滚。
 */
@DisplayName("字典批量查询：selectDictTypeByIds / selectAssignedDictTypes（真实 MySQL，事务回滚）")
public class SysDictBatchIntegrationTest extends BaseMapperIntegrationTest {
    @Autowired
    private SysDictTypeMapper dictTypeMapper;

    @Autowired
    private SysDictDataMapper dictDataMapper;

    @Test
    @DisplayName("按 id 批量回查类型；批量筛出已分配类型")
    void selectByIdsAndAssignedTypes() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String typeAssigned = "it_dict_assigned_" + suffix; // 挂一条数据 -> 已分配
        String typeFree = "it_dict_free_" + suffix; // 无数据 -> 未分配

        Long idAssigned = insertDictType("集成测试已分配", typeAssigned);
        Long idFree = insertDictType("集成测试未分配", typeFree);
        insertDictData(typeAssigned);

        // 1) 按 id 批量回查，应恰好拿到这两条
        List<SysDictType> loaded = dictTypeMapper.selectDictTypeByIds(new Long[] { idAssigned, idFree });
        assertEquals(2, loaded.size(), "selectDictTypeByIds 应返回全部命中的类型");

        // 2) 在候选类型中筛出已分配的：typeAssigned 有数据、typeFree 与不存在的类型无数据
        List<String> assigned = dictDataMapper.selectAssignedDictTypes(
                Arrays.asList(typeAssigned, typeFree, "it_dict_none_" + suffix));
        assertEquals(1, assigned.size(), "只应有一个类型被判定为已分配");
        assertTrue(assigned.contains(typeAssigned), "被占用类型必须出现在已分配结果中");
    }

    // ---- fixture helpers ----

    private Long insertDictType(String name, String type) {
        jdbc.update("insert into sys_dict_type(dict_name, dict_type, status, create_by) values(?, ?, '0', 'itest')",
                name, type);
        return jdbc.queryForObject("select dict_id from sys_dict_type where dict_type = ?", Long.class, type);
    }

    private void insertDictData(String type) {
        jdbc.update("insert into sys_dict_data(dict_sort, dict_label, dict_value, dict_type, status, create_by) "
                + "values(0, ?, ?, ?, '0', 'itest')", "集成标签", "1", type);
    }
}
