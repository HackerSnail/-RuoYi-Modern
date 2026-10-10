package com.ruoyi.generator.mapper;

import java.util.List;
import com.ruoyi.generator.domain.GenTable;

/**
 * 业务 数据层
 * 
 * @author ruoyi
 */
public interface GenTableMapper {
    /**
     * 查询已导入的生成表列表（数据源：gen_table 配置表）
     * 
     * @param genTable 业务信息（含表名/备注模糊、时间范围等查询条件）
     * @return 业务集合
     */
    public List<GenTable> selectGenTableList(GenTable genTable);

    /**
     * 查询当前库中可导入的实表列表（数据源：information_schema.tables，排除 qrtz_/gen_ 前缀及已导入表）
     * 
     * @param genTable 业务信息（含表名/备注模糊、时间范围等查询条件）
     * @return 数据库表集合
     */
    public List<GenTable> selectDbTableList(GenTable genTable);

    /**
     * 按表名数组从 information_schema 批量查询待导入实表（导入预览用）
     * 
     * @param tableNames 表名称组
     * @return 数据库表集合
     */
    public List<GenTable> selectDbTableListByNames(String[] tableNames);

    /**
     * 查询所有生成表及其列配置（一次性 LEFT JOIN 加载，用于批量生成）
     * 
     * @return 表信息集合
     */
    public List<GenTable> selectGenTableAll();

    /**
     * 按主键ID查询生成表（LEFT JOIN 装配列配置集合）
     * 
     * @param id 业务ID
     * @return 业务信息
     */
    public GenTable selectGenTableById(Long id);

    /**
     * 按表名查询生成表（LEFT JOIN 装配列配置，用于生成时定位表结构）
     * 
     * @param tableName 表名称
     * @return 业务信息
     */
    public GenTable selectGenTableByName(String tableName);

    /**
     * 新增生成表配置（回写自增主键到 tableId）
     * 
     * @param genTable 业务信息
     * @return 结果
     */
    public int insertGenTable(GenTable genTable);

    /**
     * 修改生成表配置（动态 SET 仅更新非空字段）
     * 
     * @param genTable 业务信息
     * @return 结果
     */
    public int updateGenTable(GenTable genTable);

    /**
     * 按ID批量删除生成表配置（物理删除）
     * 
     * @param ids 需要删除的数据ID
     * @return 结果
     */
    public int deleteGenTableByIds(Long[] ids);

    /**
     * 创建表
     *
     * @param sql 表结构
     * @return 结果
     */
    public int createTable(String sql);

    /**
     * 校验当前库中是否已存在同名实表（建表前防覆盖）
     *
     * @param tableName 表名称
     * @return 匹配行数（>0 即存在）
     */
    public int checkTableExist(String tableName);
}
