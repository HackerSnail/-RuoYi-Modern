package com.ruoyi.generator.service;

import java.util.List;
import com.ruoyi.generator.domain.GenTableColumn;

/**
 * 业务字段 服务层
 * 
 * @author ruoyi
 */
public interface IGenTableColumnService {
    /**
     * 按生成表ID查询其列配置列表
     * 
     * @param tableId 生成表ID
     * @return 业务字段集合
     */
    public List<GenTableColumn> selectGenTableColumnListByTableId(Long tableId);

    /**
     * 新增列配置
     * 
     * @param genTableColumn 业务字段信息
     * @return 结果
     */
    public int insertGenTableColumn(GenTableColumn genTableColumn);

    /**
     * 修改列配置
     * 
     * @param genTableColumn 业务字段信息
     * @return 结果
     */
    public int updateGenTableColumn(GenTableColumn genTableColumn);

    /**
     * 按生成表ID（table_id）批量删除其列配置（入参为生成表ID table_id 的逗号分隔串，删除的是这些表下的全部列，非按列主键删）
     * 
     * @param tableIds 需要清理列配置的生成表ID，逗号分隔
     * @return 结果
     */
    public int deleteGenTableColumnByTableIds(String tableIds);
}
