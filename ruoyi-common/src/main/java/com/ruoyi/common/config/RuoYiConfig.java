package com.ruoyi.common.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;

/**
 * 读取项目相关配置
 * <p>
 * 关于 profile / addressEnabled / captchaType 三个字段为何是 static：
 * 这些配置要在无 Spring 上下文的静态工具里读取（ExcelUtil、FileUploadUtils、
 * ImageUtils、AddressUtils 等，其中 FileUploadUtils 的静态字段在类初始化阶段就取值），
 * 因此保留静态 getter 供 {@code RuoYiConfig.getProfile()} 直接调用；
 * 而对应的 setter 刻意写成实例方法（内部写静态字段），专门留给
 * {@link ConfigurationProperties} 按 JavaBean 方式绑定 yml。
 * <p>
 * 维护注意：本类不要改成 Lombok 的 {@code @Data}——Lombok 的
 * Getter/Setter/ToString/EqualsAndHashCode 一律跳过 static 字段，改完只会覆盖
 * name/version/copyrightYear 三个字段，静态部分的 accessor 仍要手写，
 * 反而让一个类里混两套风格；项目当前也没有引入 lombok 依赖。
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "ruoyi")
public class RuoYiConfig
{
    /** 项目名称 */
    private String name;

    /** 版本 */
    private String version;

    /** 版权年份 */
    private String copyrightYear;

    /** 上传路径 */
    private static String profile;

    /** 获取地址开关 */
    private static boolean addressEnabled;

    /** 验证码类型 */
    private static String captchaType;

    /**
     * 单次导出最大条数（yml: ruoyi.exportMaxRows），未配置时用默认值 50000
     * <p>
     * 导出接口不分页（不调 startPage），会一次性查出全部匹配数据。数据量上到几十万/百万级时，
     * 会把整表行拉进 JVM 堆、再交给 POI 生成超大文件，轻则接口超时、重则 OOM 拖垮实例。
     * 超过该上限直接拒绝并提示缩小查询条件。同样保留静态字段：ExcelUtil 与
     * BaseController#exportLimit 都要在无上下文的工具代码里读取。
     */
    private static int exportMaxRows = 50000;

    /**
     * 操作日志保留天数（yml: ruoyi.operLogRetentionDays），未配置时用默认值 90
     * <p>
     * 供定时清理任务使用：只删除 oper_time 早于 (当前时间 - 保留天数) 的记录。
     * 同样用静态字段：定时任务 bean 与 Service 都要在无上下文的代码路径里读取。
     */
    private static int operLogRetentionDays = 90;

    /**
     * 允许通过公开接口 {@code /system/config/configKey/{key}} 读取的参数键白名单
     * （yml: ruoyi.publicConfigKeys，逗号分隔）
     * <p>
     * 该接口刻意不加 {@code @PreAuthorize}：前端登录页要在用户还没任何权限时读取
     * 验证码/注册开关。代价是"任何登录用户都能读到任意参数值"，一旦有人在配置里
     * 存了第三方密钥之类的敏感值就会被拖走。因此用白名单把可读范围收敛到几个
     * 无害的展示型开关，需要放行的键显式加到配置里。
     */
    private static volatile Set<String> publicConfigKeys = Set.of("sys.account.captchaEnabled",
            "sys.account.registerUser");

    /**
     * 需要鉴权才能读取的敏感字典类型（yml: ruoyi.sensitiveDictTypes，逗号分隔），默认为空
     * <p>
     * {@code /system/dict/data/type/{dictType}} 与公开配置接口同理，刻意不加
     * {@code @PreAuthorize}——前端初始化下拉框要批量加载大量字典，逐个鉴权不现实。
     * 代价是任何登录用户都能枚举全部字典类型。这里用"敏感字典黑名单"做兜底：
     * 默认全放行（不影响现有页面），把确实含敏感内容的字典类型登记进来即对该类型启用
     * {@code system:dict:list} 校验。
     */
    private static volatile Set<String> sensitiveDictTypes = Collections.emptySet();

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion(String version)
    {
        this.version = version;
    }

    public String getCopyrightYear()
    {
        return copyrightYear;
    }

    public void setCopyrightYear(String copyrightYear)
    {
        this.copyrightYear = copyrightYear;
    }

    public static String getProfile()
    {
        return profile;
    }

    public void setProfile(String profile)
    {
        RuoYiConfig.profile = profile;
    }

    public static boolean isAddressEnabled()
    {
        return addressEnabled;
    }

    public void setAddressEnabled(boolean addressEnabled)
    {
        RuoYiConfig.addressEnabled = addressEnabled;
    }

    public static String getCaptchaType() {
        return captchaType;
    }

    public void setCaptchaType(String captchaType) {
        RuoYiConfig.captchaType = captchaType;
    }

    public static int getExportMaxRows() {
        return exportMaxRows;
    }

    public static int getOperLogRetentionDays() {
        return operLogRetentionDays;
    }

    public static Set<String> getPublicConfigKeys() {
        return publicConfigKeys;
    }

    /**
     * 绑定 ruoyi.publicConfigKeys（逗号分隔）。空值直接抛异常：
     * 留空等于把白名单清空、公开接口谁都读不到，前端登录页会直接拿不到开关值。
     */
    public void setPublicConfigKeys(String publicConfigKeys) {
        Set<String> parsed = parseKeys(publicConfigKeys);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("ruoyi.publicConfigKeys 不能为空，至少保留前端登录页需要的开关");
        }
        RuoYiConfig.publicConfigKeys = parsed;
    }

    public static Set<String> getSensitiveDictTypes() {
        return sensitiveDictTypes;
    }

    /**
     * 绑定 ruoyi.sensitiveDictTypes（逗号分隔）。允许为空：空集表示所有字典按原逻辑开放
     */
    public void setSensitiveDictTypes(String sensitiveDictTypes) {
        RuoYiConfig.sensitiveDictTypes = parseKeys(sensitiveDictTypes);
    }

    /**
     * 解析逗号分隔的键串为不可变集合，空串/null 一律得到空集合
     */
    private static Set<String> parseKeys(String keys) {
        if (StringUtils.isEmpty(keys)) {
            return Collections.emptySet();
        }
        Set<String> parsed = new HashSet<>();
        for (String key : keys.split(",")) {
            String trimmed = key.trim();
            if (StringUtils.isNotEmpty(trimmed)) {
                parsed.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    /**
     * 绑定 ruoyi.operLogRetentionDays。与导出上限同理，非法值直接抛异常：
     * 配成 0 或负数会让定时清理把全表删空，必须在启动期就暴露。
     */
    public void setOperLogRetentionDays(int operLogRetentionDays) {
        if (operLogRetentionDays <= 0) {
            throw new IllegalArgumentException("ruoyi.operLogRetentionDays 必须大于 0，当前值：" + operLogRetentionDays);
        }
        RuoYiConfig.operLogRetentionDays = operLogRetentionDays;
    }

    /**
     * 绑定 ruoyi.exportMaxRows。这里对非法值直接抛异常而不是静默兜底：
     * 配置成 0 或负数会让导出保护形同虚设，属于必须暴露的配置错误，启动期失败好过线上 OOM。
     */
    public void setExportMaxRows(int exportMaxRows) {
        if (exportMaxRows <= 0) {
            throw new IllegalArgumentException("ruoyi.exportMaxRows 必须大于 0，当前值：" + exportMaxRows);
        }
        RuoYiConfig.exportMaxRows = exportMaxRows;
    }

    /**
     * 获取导入上传路径
     */
    public static String getImportPath()
    {
        return getProfile() + "/import";
    }

    /**
     * 获取头像上传路径
     */
    public static String getAvatarPath()
    {
        return getProfile() + "/avatar";
    }

    /**
     * 获取下载路径
     */
    public static String getDownloadPath()
    {
        return getProfile() + "/download/";
    }

    /**
     * 获取上传路径
     */
    public static String getUploadPath()
    {
        return getProfile() + "/upload";
    }
}
