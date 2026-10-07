package com.ruoyi.generator.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.generator.domain.GenTable;

/**
 * 业务 服务层
 * 
 * @author ruoyi
 */
public interface IGenTableService {
    /**
     * 查询已导入的生成表列表（数据源：gen_table 配置表）
     * 
     * @param genTable 业务信息
     * @return 业务集合
     */
    public List<GenTable> selectGenTableList(GenTable genTable);

    /**
     * 查询当前库中可导入的实表列表（数据源：information_schema.tables）
     * 
     * @param genTable 业务信息
     * @return 数据库表集合
     */
    public List<GenTable> selectDbTableList(GenTable genTable);

    /**
     * 按表名数组从 information_schema 批量查询待导入实表
     * 
     * @param tableNames 表名称组
     * @return 数据库表集合
     */
    public List<GenTable> selectDbTableListByNames(String[] tableNames);

    /**
     * 查询所有生成表及其列配置（用于批量生成）
     * 
     * @return 表信息集合
     */
    public List<GenTable> selectGenTableAll();

    /**
     * 按主键ID查询生成表（含列配置）
     * 
     * @param id 业务ID
     * @return 业务信息
     */
    public GenTable selectGenTableById(Long id);

    /**
     * 修改生成表配置（先经 validateEdit 校验后写库）
     * 
     * @param genTable 业务信息
     * @return 结果
     */
    public void updateGenTable(GenTable genTable);

    /**
     * 按ID批量删除生成表，并级联清理其列配置
     * 
     * @param tableIds 需要删除的表数据ID
     * @return 结果
     */
    public void deleteGenTableByIds(Long[] tableIds);

    /**
     * 执行建表 DDL（根据生成预览的 SQL 在真实库建表）
     *
     * @param sql 创建表语句
     * @return 建表是否成功
     */
    public boolean createTable(String sql);

    /**
     * 导入表结构：从 information_schema 读取实表元数据并写入 gen_table/gen_table_column
     *
     * @param tableList  导入表列表
     * @param tplWebType 前端模板类型
     * @param operName   操作人员
     */
    public void importGenTable(List<GenTable> tableList, String tplWebType, String operName);

    /**
     * 预览代码：渲染各模板返回模板名→生成内容的映射（不落盘）
     * 
     * @param tableId 表编号
     * @return 预览数据列表
     */
    public Map<String, String> previewCode(Long tableId);

    /**
     * 生成代码并打包为 zip 字节数组（下载方式）
     * 
     * @param tableName 表名称
     * @return zip 字节数据
     */
    public byte[] downloadCode(String tableName);

    /**
     * 生成代码并写入本地自定义路径（仅当 allowOverwrite 开启时生效）
     * 
     * @param tableName 表名称
     */
    public void generatorCode(String tableName);

    /**
     * 同步数据库：重新读取实表结构，补齐新增列、移除已删列，保留已有列的人工配置
     * 
     * @param tableName 表名称
     */
    public void synchDb(String tableName);

    /**
     * 批量生成代码并打包为 zip 字节数组（下载方式）
     * 
     * @param tableNames 表数组
     * @return zip 字节数据
     */
    public byte[] downloadCode(String[] tableNames);

    /**
     * 修改保存前的参数校验：校验包名/模块名/业务名/类名/模板等合法性，非法则抛异常
     * 
     * @param genTable 业务信息
     */
    public void validateEdit(GenTable genTable);
}
