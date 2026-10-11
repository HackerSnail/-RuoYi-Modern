package com.ruoyi.system.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * 字典 业务层处理
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class SysDictDataServiceImpl implements ISysDictDataService {
    /**
     * 按字典类型分段加锁，串行化"重拉全量 + 写缓存"临界区，消除并发覆盖错序。
     * 仅约束单 JVM；跨 JVM 的极端并发依赖读侧 cache-aside 自愈。dictType 数量有限，map 不会无界增长。
     */
    private static final ConcurrentHashMap<String, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

    private final SysDictDataMapper dictDataMapper;

    private final ISysDictTypeService dictTypeService;

    public SysDictDataServiceImpl(SysDictDataMapper dictDataMapper, ISysDictTypeService dictTypeService) {
        this.dictDataMapper = dictDataMapper;
        this.dictTypeService = dictTypeService;
    }

    /**
     * 根据条件分页查询字典数据
     * 
     * @param dictData 字典数据信息
     * @return 字典数据集合信息
     */
    @Override
    public List<SysDictData> selectDictDataList(SysDictData dictData) {
        return dictDataMapper.selectDictDataList(dictData);
    }

    /**
     * 根据字典类型和字典键值查询字典数据信息
     * 
     * @param dictType  字典类型
     * @param dictValue 字典键值
     * @return 字典标签
     */
    @Override
    public String selectDictLabel(String dictType, String dictValue) {
        return dictDataMapper.selectDictLabel(dictType, dictValue);
    }

    /**
     * 根据字典数据ID查询信息
     * 
     * @param dictCode 字典数据ID
     * @return 字典数据
     */
    @Override
    public SysDictData selectDictDataById(Long dictCode) {
        return dictDataMapper.selectDictDataById(dictCode);
    }

    /**
     * 批量删除字典数据信息
     * 
     * @param dictCodes 需要删除的字典数据ID
     */
    @Override
    @Transactional
    public void deleteDictDataByIds(Long[] dictCodes) {
        if (StringUtils.isNull(dictCodes) || dictCodes.length == 0) {
            return;
        }
        // 第一趟：整批预校验，任一记录不存在（或已被并发删除）即抛业务异常回滚，避免"部分删除"；
        // 同时去重收集受影响的字典类型（保持删除顺序稳定性用 LinkedHashSet）
        Set<String> affectedTypes = new LinkedHashSet<>();
        for (Long dictCode : dictCodes) {
            SysDictData data = selectDictDataById(dictCode);
            if (StringUtils.isNull(data)) {
                throw new ServiceException("字典数据不存在或已被删除，dictCode=" + dictCode);
            }
            affectedTypes.add(data.getDictType());
        }
        // 第二趟：DB 删除在同一事务内完成
        for (Long dictCode : dictCodes) {
            dictDataMapper.deleteDictDataById(dictCode);
        }
        // 缓存按去重类型刷新一次，减少 Redis 往返；某类型已无正常数据时移除条目而非写空列表
        for (String dictType : affectedTypes) {
            refreshDictCache(dictType);
        }
    }

    /**
     * 新增保存字典数据信息
     * 
     * @param data 字典数据信息
     * @return 结果
     */
    @Override
    public int insertDictData(SysDictData data) {
        // 存在性校验：向不存在的字典类型下写数据会产生孤儿字典项
        assertDictTypeExists(data.getDictType());
        // 唯一性纵深：Controller 已前置校验，此处收口，防止其它调用方绕过；DB (dict_type,dict_value) UNIQUE 兜底 TOCTOU
        if (!checkDictValueUnique(data)) {
            throw new ServiceException(
                    "新增字典数据'" + data.getDictLabel() + "'失败，同类型下键值已存在：" + data.getDictValue());
        }
        int row = dictDataMapper.insertDictData(data);
        if (row > 0) {
            // 写库后按字典类型重拉全量并刷新缓存，保证前端下拉与新增项一致
            refreshDictCache(data.getDictType());
        }
        return row;
    }

    /**
     * 修改保存字典数据信息
     * 
     * @param data 字典数据信息
     * @return 结果
     */
    @Override
    public int updateDictData(SysDictData data) {
        assertDictTypeExists(data.getDictType());
        if (!checkDictValueUnique(data)) {
            throw new ServiceException(
                    "修改字典数据'" + data.getDictLabel() + "'失败，同类型下键值已存在：" + data.getDictValue());
        }
        int row = dictDataMapper.updateDictData(data);
        if (row > 0) {
            refreshDictCache(data.getDictType());
        }
        return row;
    }

    /**
     * 校验字典类型在 sys_dict_type 中存在，防止孤儿字典项
     */
    private void assertDictTypeExists(String dictType) {
        if (StringUtils.isEmpty(dictType) || StringUtils.isNull(dictTypeService.selectDictTypeByType(dictType))) {
            throw new ServiceException("字典类型不存在：" + dictType);
        }
    }

    /**
     * 按字典类型串行化刷新缓存：重拉全量，有数据则覆盖写，已空则移除条目。
     */
    private void refreshDictCache(String dictType) {
        Object lock = CACHE_LOCKS.computeIfAbsent(dictType, k -> new Object());
        synchronized (lock) {
            List<SysDictData> dictDatas = dictDataMapper.selectDictDataByType(dictType);
            if (StringUtils.isNotEmpty(dictDatas)) {
                DictUtils.setDictCache(dictType, dictDatas);
            } else {
                DictUtils.removeDictCache(dictType);
            }
        }
    }

    /**
     * 校验同一字典类型下 dictValue 是否唯一。更新时依据 dictCode 排除自己；
     * 新增时 dictCode 为 null，统一归到 -1L 与已存在记录的 dictCode 对比。
     */
    @Override
    public boolean checkDictValueUnique(SysDictData dictData) {
        Long dictCode = StringUtils.isNull(dictData.getDictCode()) ? -1L : dictData.getDictCode();
        SysDictData info = dictDataMapper.checkDictValueUnique(dictData.getDictType(), dictData.getDictValue());
        if (StringUtils.isNotNull(info) && info.getDictCode().longValue() != dictCode.longValue()) {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }
}
