package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.mapper.SysConfigMapper;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 参数配置 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class SysConfigServiceImpl implements ISysConfigService {
    private static final Logger logger = LoggerFactory.getLogger(SysConfigServiceImpl.class);

    /**
     * 空值哨兵：回源确认某键不存在时回填的占位，命中即直接返回空，阻断对不存在键的反复回源。
     * 取不可能成为真实 configValue 的字面量（真实值写入侧受 @NotBlank 约束、不会等于此串）。
     */
    private static final String NULL_SENTINEL = "__CONFIG_NULL__";

    /** 空值哨兵的短 TTL（秒）：新增同名配置后最多此时长自然失效、无需手动清缓存即可读到真值。 */
    private static final int NULL_CACHE_TTL_SECONDS = 60;

    private final SysConfigMapper configMapper;

    private final RedisCache redisCache;

    public SysConfigServiceImpl(SysConfigMapper configMapper, RedisCache redisCache) {
        this.configMapper = configMapper;
        this.redisCache = redisCache;
    }

    /**
     * 项目启动时，初始化参数到缓存
     */
    @PostConstruct
    public void init() {
        loadingConfigCache();
    }

    /**
     * 查询参数配置信息
     * 
     * @param configId 参数配置ID
     * @return 参数配置信息
     */
    @Override
    public SysConfig selectConfigById(Long configId) {
        SysConfig config = new SysConfig();
        config.setConfigId(configId);
        return configMapper.selectConfig(config);
    }

    /**
     * 根据键名查询参数配置信息
     * 
     * @param configKey 参数key
     * @return 参数键值
     */
    @Override
    public String selectConfigByKey(String configKey) {
        // 读穿式缓存（cache-aside）：优先取 Redis。区分三种命中——空值哨兵、真实值、未命中。
        Object cached = redisCache.getCacheObject(getCacheKey(configKey));
        if (cached != null) {
            String cachedValue = Convert.toStr(cached);
            // 命中空值哨兵：此前已回源确认该键不存在，直接返回空，不再打 DB
            if (NULL_SENTINEL.equals(cachedValue)) {
                return StringUtils.EMPTY;
            }
            if (StringUtils.isNotEmpty(cachedValue)) {
                return cachedValue;
            }
        }
        SysConfig config = new SysConfig();
        config.setConfigKey(configKey);
        SysConfig retConfig = configMapper.selectConfig(config);
        if (StringUtils.isNotNull(retConfig)) {
            redisCache.setCacheObject(getCacheKey(configKey), retConfig.getConfigValue());
            return retConfig.getConfigValue();
        }
        // 回源确认不存在：回填短 TTL 空值哨兵，阻断对不存在键的反复穿透
        redisCache.setCacheObject(getCacheKey(configKey), NULL_SENTINEL, NULL_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return StringUtils.EMPTY;
    }

    /**
     * 公开接口读取参数值：先过白名单再取值
     */
    @Override
    public String selectPublicConfigByKey(String configKey) {
        if (!RuoYiConfig.getPublicConfigKeys().contains(configKey)) {
            // 记 warn：既能发现前端误用/漏配白名单，也能留下有人在探测其它配置键的痕迹
            logger.warn("参数键[{}]不在公开读取白名单内，已拒绝", configKey);
            // 对外只回通用文案：具体 key 已由上面 warn 落日志，message 不再拼回用户可控的 configKey，
            // 免得原样回传后被前端/日志页渲染成 XSS / 日志注入面
            throw new ServiceException("参数键不允许通过公开接口读取");
        }
        return selectConfigByKey(configKey);
    }

    /**
     * 获取验证码开关
     * 
     * @return true开启，false关闭
     */
    @Override
    public boolean selectCaptchaEnabled() {
        String captchaEnabled = selectConfigByKey("sys.account.captchaEnabled");
        if (StringUtils.isEmpty(captchaEnabled)) {
            return true;
        }
        return Convert.toBool(captchaEnabled);
    }

    /**
     * 查询参数配置列表
     * 
     * @param config 参数配置信息
     * @return 参数配置集合
     */
    @Override
    public List<SysConfig> selectConfigList(SysConfig config) {
        return configMapper.selectConfigList(config);
    }

    /**
     * 新增参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public int insertConfig(SysConfig config) {
        // 纵深防御：唯一性收口在 Service（控制层已校一道），防未来新增非控制层调用路径绕过。
        // 否侧两个不同 configId 可写入同一 configKey，Redis 缓存键被后写者覆盖、配置读取错乱。
        if (!checkConfigKeyUnique(config)) {
            throw new ServiceException("新增参数失败，参数键名已存在：" + config.getConfigKey());
        }
        int row = configMapper.insertConfig(config);
        if (row > 0) {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
        return row;
    }

    /**
     * 修改参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public int updateConfig(SysConfig config) {
        SysConfig temp = configMapper.selectConfigById(config.getConfigId());
        // 回查为空（configId 不存在或已被并发删除）直接抛业务异常，避免下面 temp.getConfigKey() NPE 打 500
        if (StringUtils.isNull(temp)) {
            throw new ServiceException("参数不存在或已被删除");
        }
        // 纵深防御：唯一性收口在 Service（控制层已校一道），同 insertConfig
        if (!checkConfigKeyUnique(config)) {
            throw new ServiceException("修改参数失败，参数键名已存在：" + config.getConfigKey());
        }
        if (!StringUtils.equals(temp.getConfigKey(), config.getConfigKey())) {
            // 参数键名被修改时，需删除旧键对应的缓存，避免旧键值残留
            redisCache.deleteObject(getCacheKey(temp.getConfigKey()));
        }

        int row = configMapper.updateConfig(config);
        if (row > 0) {
            // 取舍：参数配置为低频管理操作，此处“先更新 DB 再写新缓存”的新值复活窗口可忽略，不引入延迟双删
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
        return row;
    }

    /**
     * 批量删除参数信息
     * 
     * @param configIds 需要删除的参数ID
     */
    @Override
    public void deleteConfigByIds(Long[] configIds) {
        // 先整批预校验：任一 id 不存在或命中内置项，都在执行任何删除前统一拒绝。
        // 避免循环中途抛异常导致“前面已物理删+已清缓存、后面拒绝”的部分删除（此处无事务、回滚不掉）。
        List<SysConfig> targets = new ArrayList<>(configIds.length);
        for (Long configId : configIds) {
            SysConfig config = selectConfigById(configId);
            if (StringUtils.isNull(config)) {
                throw new ServiceException("参数不存在或已被删除，configId=" + configId);
            }
            if (StringUtils.equals(UserConstants.YES, config.getConfigType())) {
                throw new ServiceException(String.format("内置参数【%1$s】不能删除 ", config.getConfigKey()));
            }
            targets.add(config);
        }
        // 预校验全部通过后再统一执行删除
        for (SysConfig config : targets) {
            configMapper.deleteConfigById(config.getConfigId());
            redisCache.deleteObject(getCacheKey(config.getConfigKey()));
        }
    }

    /**
     * 加载参数缓存数据
     */
    @Override
    public void loadingConfigCache() {
        List<SysConfig> configsList = configMapper.selectConfigList(new SysConfig());
        for (SysConfig config : configsList) {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
    }

    /**
     * 清空参数缓存数据
     */
    @Override
    public void clearConfigCache() {
        // SCAN + 分批 UNLINK：历史写法 keys(SYS_CONFIG_KEY + "*") + DEL 在大库上会阻塞 Redis 主线程
        redisCache.deleteKeysByPattern(CacheConstants.SYS_CONFIG_KEY + "*");
    }

    /**
     * 重置参数缓存数据
     */
    @Override
    public void resetConfigCache() {
        clearConfigCache();
        loadingConfigCache();
    }

    /**
     * 校验参数键名是否唯一
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public boolean checkConfigKeyUnique(SysConfig config) {
        Long configId = StringUtils.isNull(config.getConfigId()) ? -1L : config.getConfigId();
        SysConfig info = configMapper.checkConfigKeyUnique(config.getConfigKey());
        if (StringUtils.isNotNull(info) && info.getConfigId().longValue() != configId.longValue()) {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 设置cache key
     * 
     * @param configKey 参数键
     * @return 缓存键key
     */
    private String getCacheKey(String configKey) {
        return CacheConstants.SYS_CONFIG_KEY + configKey;
    }
}
