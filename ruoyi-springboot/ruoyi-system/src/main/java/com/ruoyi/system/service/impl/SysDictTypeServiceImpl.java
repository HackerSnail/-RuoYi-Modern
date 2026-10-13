package com.ruoyi.system.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * 字典 业务层处理
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class SysDictTypeServiceImpl implements ISysDictTypeService {
    /**
     * 多实例共享同一 Redis 启动灌缓存的集群锁 key：SET NX PX，仅抢到锁的节点执行全量加载，其余跳过。
     */
    private static final String STARTUP_LOCK_KEY = "ruoyi:dict:startup:loading";

    /** 启动集群锁的 TTL：即便持锁节点崩溃未释放，到期自动放开供后续节点重试 */
    private static final Duration STARTUP_LOCK_TTL = Duration.ofSeconds(30);

    private final SysDictTypeMapper dictTypeMapper;

    private final SysDictDataMapper dictDataMapper;

    private final RedisCache redisCache;

    public SysDictTypeServiceImpl(SysDictTypeMapper dictTypeMapper, SysDictDataMapper dictDataMapper,
            RedisCache redisCache) {
        this.dictTypeMapper = dictTypeMapper;
        this.dictDataMapper = dictDataMapper;
        this.redisCache = redisCache;
    }

    /**
     * 项目启动时，初始化字典到缓存。
     * <p>
     * 多 Pod 同时 @PostConstruct 会并发全量 setDictCache；因缓存是集群共享的一份，这里用 SET NX 锁让
     * 集群内仅一个节点执行全量灌入，其余跳过（未命中由读侧 cache-aside 回源自愈），消除并发覆盖。
     * 显式的 resetDictCache/clearDictCache 走未加锁的 {@link #loadingDictCache()}，以保证管理员主动重置必然生效。
     */
    @PostConstruct
    public void init() {
        Boolean acquired = redisCache.redisTemplate.opsForValue().setIfAbsent(STARTUP_LOCK_KEY, "loading", STARTUP_LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                loadingDictCache();
            } finally {
                redisCache.deleteObject(STARTUP_LOCK_KEY);
            }
        }
    }

    /**
     * 根据条件分页查询字典类型
     * 
     * @param dictType 字典类型信息
     * @return 字典类型集合信息
     */
    @Override
    public List<SysDictType> selectDictTypeList(SysDictType dictType) {
        return dictTypeMapper.selectDictTypeList(dictType);
    }

    /**
     * 根据所有字典类型
     * 
     * @return 字典类型集合信息
     */
    @Override
    public List<SysDictType> selectDictTypeAll() {
        return dictTypeMapper.selectDictTypeAll();
    }

    /**
     * 根据字典类型查询字典数据
     * 
     * @param dictType 字典类型
     * @return 字典数据集合信息
     */
    @Override
    public List<SysDictData> selectDictDataByType(String dictType) {
        // 读穿式缓存（cache-aside）：先查缓存，未命中回源 DB 并回写，后续命中缓存
        List<SysDictData> dictDatas = DictUtils.getDictCache(dictType);
        if (StringUtils.isNotEmpty(dictDatas)) {
            return dictDatas;
        }
        dictDatas = dictDataMapper.selectDictDataByType(dictType);
        if (StringUtils.isNotEmpty(dictDatas)) {
            DictUtils.setDictCache(dictType, dictDatas);
            return dictDatas;
        }
        return null;
    }

    /**
     * 根据字典类型ID查询信息
     * 
     * @param dictId 字典类型ID
     * @return 字典类型
     */
    @Override
    public SysDictType selectDictTypeById(Long dictId) {
        return dictTypeMapper.selectDictTypeById(dictId);
    }

    /**
     * 根据字典类型查询信息
     * 
     * @param dictType 字典类型
     * @return 字典类型
     */
    @Override
    public SysDictType selectDictTypeByType(String dictType) {
        return dictTypeMapper.selectDictTypeByType(dictType);
    }

    /**
     * 批量删除字典类型信息（原子优化）
     *
     * <p>
     * 将原“逐行 select+check+delete”（3N 次 DB）合并为三次批量查询，并加 @Transactional。
     * 行为变更：只要待删类型中有任一个已被字典数据占用，则整体不删除并抛出异常，
     * 消除了原代码“部分删除后才报错”的隐患。
     * </p>
     *
     * @param dictIds 需要删除的字典类型ID数组
     */
    @Override
    @Transactional
    public void deleteDictTypeByIds(Long[] dictIds) {
        if (dictIds == null || dictIds.length == 0) {
            return;
        }
        // 批量加载待删类型（1 次查询），再批量判断哪些类型已分配字典数据（1 次查询），
        // 整体校验通过后一次性批量删除，避免逐行 select+count+delete 的 N+1
        List<SysDictType> dictTypes = dictTypeMapper.selectDictTypeByIds(dictIds);
        List<String> types = dictTypes.stream().map(SysDictType::getDictType).collect(Collectors.toList());
        List<String> assigned = types.isEmpty() ? new ArrayList<>() : dictDataMapper.selectAssignedDictTypes(types);
        for (SysDictType dictType : dictTypes) {
            if (assigned.contains(dictType.getDictType())) {
                throw new ServiceException(String.format("%1$s已分配,不能删除", dictType.getDictName()));
            }
        }
        dictTypeMapper.deleteDictTypeByIds(dictIds);
        List<String> removedTypes = dictTypes.stream().map(SysDictType::getDictType).collect(Collectors.toList());
        // 缓存清理移到事务提交后：若提交前就删缓存、而事务随后回滚，读侧会回源 DB（数据仍在）又把旧值灌回缓存
        runAfterCommit(() -> removedTypes.forEach(DictUtils::removeDictCache));
    }

    /**
     * 加载字典缓存数据
     */
    @Override
    public void loadingDictCache() {
        SysDictData dictData = new SysDictData();
        dictData.setStatus(UserConstants.DICT_NORMAL);
        Map<String, List<SysDictData>> dictDataMap = dictDataMapper.selectDictDataList(dictData).stream()
                .collect(Collectors.groupingBy(SysDictData::getDictType));
        for (Map.Entry<String, List<SysDictData>> entry : dictDataMap.entrySet()) {
            DictUtils.setDictCache(entry.getKey(), entry.getValue().stream()
                    .sorted(Comparator.comparing(SysDictData::getDictSort)).collect(Collectors.toList()));
        }
    }

    /**
     * 清空字典缓存数据
     */
    @Override
    public void clearDictCache() {
        DictUtils.clearDictCache();
    }

    /**
     * 重置字典缓存数据
     */
    @Override
    public void resetDictCache() {
        clearDictCache();
        loadingDictCache();
    }

    /**
     * 新增保存字典类型信息
     * 
     * @param dict 字典类型信息
     * @return 结果
     */
    @Override
    public int insertDictType(SysDictType dict) {
        // 唯一性 Service 层收口（纵深）：Controller 已前置 checkDictTypeUnique，此处防其它调用方/TOCTOU 绕过
        if (!checkDictTypeUnique(dict)) {
            throw new ServiceException("新增字典'" + dict.getDictName() + "'失败，字典类型已存在：" + dict.getDictType());
        }
        int row = dictTypeMapper.insertDictType(dict);
        if (row > 0) {
            // 新类型尚无字典数据，先写入空缓存占位，避免每次查询都回源未命中的 DB
            DictUtils.setDictCache(dict.getDictType(), null);
        }
        return row;
    }

    /**
     * 修改保存字典类型信息
     * 
     * @param dict 字典类型信息
     * @return 结果
     */
    @Override
    @Transactional
    public int updateDictType(SysDictType dict) {
        // 改名目标唯一性 Service 层收口（纵深）：防止 A→B 的级联把两个字典的数据搅在一起
        if (!checkDictTypeUnique(dict)) {
            throw new ServiceException("修改字典'" + dict.getDictName() + "'失败，字典类型已存在：" + dict.getDictType());
        }
        SysDictType oldDict = dictTypeMapper.selectDictTypeById(dict.getDictId());
        if (StringUtils.isNull(oldDict)) {
            throw new ServiceException("字典类型不存在或已被删除，dictId=" + dict.getDictId());
        }
        // 类型标识可能被改名，需级联更新已归属字典数据的 dictType，再按新类型刷新缓存
        dictDataMapper.updateDictDataType(oldDict.getDictType(), dict.getDictType());
        int row = dictTypeMapper.updateDictType(dict);
        if (row > 0) {
            String newType = dict.getDictType();
            // 缓存在事务提交后再刷新：避免提交失败回滚却留下按新名写入的脏缓存
            runAfterCommit(() -> {
                List<SysDictData> dictDatas = dictDataMapper.selectDictDataByType(newType);
                DictUtils.setDictCache(newType, dictDatas);
            });
        }
        return row;
    }

    /**
     * 事务提交成功后执行 action；无活动事务时立即执行（兜底，避免非事务调用路径下缓存永不刷新）。
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 校验字典类型称是否唯一
     * 
     * @param dict 字典类型
     * @return 结果
     */
    @Override
    public boolean checkDictTypeUnique(SysDictType dict) {
        Long dictId = StringUtils.isNull(dict.getDictId()) ? -1L : dict.getDictId();
        SysDictType dictType = dictTypeMapper.checkDictTypeUnique(dict.getDictType());
        if (StringUtils.isNotNull(dictType) && dictType.getDictId().longValue() != dictId.longValue()) {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }
}
