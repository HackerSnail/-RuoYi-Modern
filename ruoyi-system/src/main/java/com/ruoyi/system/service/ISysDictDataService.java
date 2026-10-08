package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysDictData;

/**
 * 字典 业务层
 * 
 * @author ruoyi
 */
public interface ISysDictDataService {
    /**
     * 根据条件分页查询字典数据
     * 
     * @param dictData 字典数据信息
     * @return 字典数据集合信息
     */
    public List<SysDictData> selectDictDataList(SysDictData dictData);

    /**
     * 根据字典类型和字典键值查询字典数据信息
     * 
     * @param dictType  字典类型
     * @param dictValue 字典键值
     * @return 字典标签
     */
    public String selectDictLabel(String dictType, String dictValue);

    /**
     * 根据字典数据ID查询信息
     * 
     * @param dictCode 字典数据ID
     * @return 字典数据
     */
    public SysDictData selectDictDataById(Long dictCode);

    /**
     * 批量删除字典数据信息
     * 
     * @param dictCodes 需要删除的字典数据ID
     */
    public void deleteDictDataByIds(Long[] dictCodes);

    /**
     * 新增保存字典数据信息
     * 
     * @param dictData 字典数据信息
     * @return 结果
     */
    public int insertDictData(SysDictData dictData);

    /**
     * 修改保存字典数据信息
     * 
     * @param dictData 字典数据信息
     * @return 结果
     */
    public int updateDictData(SysDictData dictData);

    /**
     * 校验同一字典类型下 dictValue 是否唯一（新增/修改前置检查）
     * <p>
     * 前端下拉框基于 dictValue 定位选中项，同一 dictType 下重复的 dictValue
     * 会造成选项重影、默认值错乱；更新场景自动排除自己（dictCode 比对）。
     * 
     * @param dictData 待写入的字典数据
     * @return true=唯一，false=同类型下已存在相同键值
     */
    public boolean checkDictValueUnique(SysDictData dictData);
}
