package com.ruoyi.generator.mapper;

import java.util.List;
import com.ruoyi.generator.domain.GenTableColumn;

/**
 * 业务字段 数据层
 * 
 * @author ruoyi
 */
public interface GenTableColumnMapper {
    /**
     * 从 information_schema.columns 反向读取实表的列结构（导入表时用，CASE 推导
     * is_pk/is_required/is_increment）
     * 
     * @param tableName 表名称
     * @return 列信息
     */
    public List<GenTableColumn> selectDbTableColumnsByName(String tableName);

    /**
     * 按生成表ID查询其已保存的列配置（按 sort 排序，用于预览/编辑时加载）
     * 
     * @param tableId 生成表ID
     * @return 业务字段集合
     */
    public List<GenTableColumn> selectGenTableColumnListByTableId(Long tableId);

    /**
     * 新增列配置（回写自增主键到 columnId）
     * 
     * @param genTableColumn 业务字段信息
     * @return 结果
     */
    public int insertGenTableColumn(GenTableColumn genTableColumn);

    /**
     * 修改列配置（动态 SET 仅更新非 null 字段）
     * 
     * @param genTableColumn 业务字段信息
     * @return 结果
     */
    public int updateGenTableColumn(GenTableColumn genTableColumn);

    /**
     * 按列对象列表批量删除指定列
     * 
     * @param genTableColumns 列数据
     * @return 结果
     */
    public int deleteGenTableColumns(List<GenTableColumn> genTableColumns);

    /**
     * 按生成表ID批量删除其列配置（删除/重新导入生成表时级联清理）
     * 
     * @param ids 需要删除的生成表ID
     * @return 结果
     */
    public int deleteGenTableColumnByIds(Long[] ids);
}
