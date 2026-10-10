package com.ruoyi.generator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
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
    public List<GenTableColumn> selectDbTableColumnsByName(@Param("tableName") String tableName);

    /**
     * 按生成表ID查询其已保存的列配置（按 sort 排序，用于预览/编辑时加载）
     * 
     * @param tableId 生成表ID
     * @return 业务字段集合
     */
    public List<GenTableColumn> selectGenTableColumnListByTableId(@Param("tableId") Long tableId);

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
     * 批量新增列配置（导入表时将多列合并为一条多值 INSERT，减少往返；不回填自增主键）
     * 
     * @param columns 列数据集合
     * @return 结果
     */
    public int batchInsertGenTableColumn(@Param("columns") List<GenTableColumn> columns);

    /**
     * 按列主键（column_id）列表批量删除指定列（空集合时 XML 内退化为 1=0，不报错也不误删）
     * 
     * @param columnIds 待删列主键集合
     * @return 结果
     */
    public int deleteGenTableColumns(@Param("columnIds") List<Long> columnIds);

    /**
     * 按生成表ID批量删除其列配置（删除/重新导入生成表时级联清理；空数组时 XML 内退化为 1=0）
     * 
     * @param ids 需要删除的生成表ID
     * @return 结果
     */
    public int deleteGenTableColumnByIds(@Param("ids") Long[] ids);
}
