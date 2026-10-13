package com.ruoyi.generator.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.generator.domain.GenTable;

/**
 * 业务 服务层
 * 
 * @author HackerSnail <2306002088@qq.com>
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
     * 修改生成表配置：把 params 聚合为 options 写主表，成功后在同一事务内级联同步列配置（并校验列归属，禁跨表改写）。
     *
     * <p>
     * 注意：编辑合法性校验 {@link #validateEdit(GenTable)}
     * 由调用方（Controller.editSave）在本方法之前显式调用，
     * 本方法自身不含该校验，勿误以为调用即已校验；新增/导入路径不经过 validateEdit。
     * </p>
     *
     * @param genTable 业务信息
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
     * 执行建表 DDL（根据生成预览的 SQL 在真实库建表）。
     *
     * <p>
     * 契约约束：实现方对入参做纵深防御——先经 {@code SqlUtil.filterKeyword} 滤危险关键字，再用 Druid 按 MySQL
     * 方言解析并仅允许<b>单条 CREATE TABLE</b>（DROP/ALTER/多语句/非建表一律拒绝），且按解析后的 AST 重序列化再执行。
     * 因此即便调用方漏做白名单，本方法也不会执行任意 SQL。防覆盖校验({@link #checkTableExist})与建表后导入编排仍由调用方负责。
     * </p>
     *
     * @param sql 创建表语句（仅接受单条 CREATE TABLE）
     * @return 建表是否成功
     */
    public boolean createTable(String sql);

    /**
     * 校验当前库中是否已存在同名实表（建表前防覆盖）
     *
     * @param tableName 表名称
     * @return 存在返回 true
     */
    public boolean checkTableExist(String tableName);

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
     * @param tableName 表名称（在 gen_table 配置表内按表名定位，须保证表名唯一）
     * @return zip 字节数据
     */
    public byte[] downloadCode(String tableName);

    /**
     * 生成代码并写入本地自定义路径（写盘位置由表配置 gen_path 与表名/模块名派生）。
     *
     * <p>
     * 安全说明：allowOverwrite 只门控“能否落盘”，并不约束“写到哪”；当前实现未做路径归一化/根目录前缀校验，
     * 故 gen_path 必须为可信值（不得含 ../），且该入口已由 {@code tool:gen:code} 权限保护。若后续放开 gen_path
     * 的自由编辑或放宽写盘门控，须补 normalize + 根目录校验。
     * </p>
     *
     * @param tableName 表名称（在 gen_table 配置表内按表名定位，须保证表名唯一）
     */
    public void generatorCode(String tableName);

    /**
     * 同步数据库：重新读取实表结构，补齐新增列、移除已删列，保留已有列的人工配置
     * 
     * @param tableName 表名称（在 gen_table 配置表内按表名定位，须保证表名唯一）
     */
    public void synchDb(String tableName);

    /**
     * 批量生成代码并打包为 zip 字节数组（下载方式）。
     *
     * <p>
     * 契约约束：实现方对单次表数量设上限（MAX_BATCH_GEN_TABLES=50），空数组或超限一律拒绝，
     * 避免逐表渲染全量驻留内存 + zip 触发 OOM/超时；调用方（Controller 批量下载）不应绕过该限制。
     * </p>
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
