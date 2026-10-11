package com.ruoyi.framework.web.service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.UserAgentUtils;
import com.ruoyi.common.utils.ip.AddressUtils;
import com.ruoyi.common.utils.sign.SignUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.SecretKey;

/**
 * token验证处理
 * 
 * @author ruoyi
 */
@Component
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    // 令牌自定义标识
    @Value("${token.header}")
    private String header;

    // 令牌有效期（默认30分钟）
    @Value("${token.expireTime}")
    private int expireTime;

    protected static final long MILLIS_SECOND = 1000;

    protected static final long MILLIS_MINUTE = 60 * MILLIS_SECOND;

    private static final Long MILLIS_MINUTE_TWENTY = 20 * 60 * 1000L;

    /** 会话标识的域分离后缀：与 JWT 签名密钥同源不同用，避免同一密钥跨算法复用 */
    private static final String SESSION_HANDLE_DOMAIN = "|online-session-handle";

    /** 下发给前端的会话标识长度（HMAC-SHA256 的 64 位 hex 取前 32 位 = 128bit） */
    private static final int SESSION_HANDLE_LENGTH = 32;

    /** 合法会话标识形态：恰好 32 位小写 hex。先按格式拒绝，避免任意串进入全量扫描 */
    private static final Pattern SESSION_HANDLE_PATTERN = Pattern.compile("[0-9a-f]{" + SESSION_HANDLE_LENGTH + "}");

    private final RedisCache redisCache;

    // HMAC 签名密钥：构造期一次性生成、final 不可变；密钥不合规在启动即 fail-fast
    private final SecretKey secretKey;

    // 会话标识派生密钥：由 token.secret 经 SHA-256 域分离派生，构造期一次性生成、final 不可变
    private final String sessionHandleKey;

    public TokenService(RedisCache redisCache, @Value("${token.secret}") String secret) {
        this.redisCache = redisCache;
        // HS512 要求密钥不少于 64 字节，过短将在此抛 WeakKeyException 阻断启动
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.sessionHandleKey = SignUtils.sha256(secret + SESSION_HANDLE_DOMAIN);
    }

    /**
     * 获取用户身份信息
     * 
     * @return 用户信息
     */
    public LoginUser getLoginUser(HttpServletRequest request) {
        // 获取请求携带的令牌
        String token = getToken(request);
        if (StringUtils.isNotEmpty(token)) {
            try {
                Claims claims = parseToken(token);
                // 解析对应的权限以及用户信息
                String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
                String userKey = getTokenKey(uuid);
                LoginUser user = redisCache.getCacheObject(userKey, LoginUser.class);
                // Redis 取不到（会话已过期或被强制下线）属正常路径：直接返回 null，交由鉴权过滤器回 401，不打日志
                return user;
            } catch (JwtException e) {
                // 令牌本身非法（篡改/签名不符/格式错误）：安全相关，warn 仅记异常类型，不拼可能含令牌片段的原文
                log.warn("令牌校验失败'{}'", e.getClass().getSimpleName());
            } catch (Exception e) {
                // 兜底：Redis 不可达等基础设施异常，记 error 供排查
                log.error("获取登录用户信息异常'{}'", e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * 设置用户身份信息
     */
    public void setLoginUser(LoginUser loginUser) {
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNotEmpty(loginUser.getToken())) {
            refreshToken(loginUser);
        }
    }

    /**
     * 删除用户身份信息
     */
    public void delLoginUser(String token) {
        if (StringUtils.isNotEmpty(token)) {
            String userKey = getTokenKey(token);
            redisCache.deleteObject(userKey);
        }
    }

    /**
     * 把会话 uuid 换算成可下发的展示用会话标识（handle）。
     * <p>
     * handle = 截断的 HMAC-SHA256(sessionHandleKey, uuid)：<b>单向不可逆</b>、不含 uuid 任何片段，且因密钥只有服务端
     * 持有，攻击者即便从在线列表拿到它，既还原不出会话键、也伪造不出通过验签的 JWT（真正的凭证是 JWT）。
     * 会话重建（重新登录）后 uuid 变、handle 跟着变，因此它不能跨会话复用。
     *
     * @param uuid 会话 uuid（Redis 键后缀，即 {@code LoginUser.getToken()}）
     * @return 脱敏会话标识；uuid 为空（残缺会话）时返回 null，前端该行自然拿不到强退入口
     */
    public String toSessionHandle(String uuid) {
        if (StringUtils.isEmpty(uuid)) {
            return null;
        }
        return SignUtils.hmacSha256(uuid, sessionHandleKey).substring(0, SESSION_HANDLE_LENGTH);
    }

    /**
     * 按脱敏会话标识强制下线的逆用路径：扫描在线会话键，对每个 uuid 重算 handle 比对，命中即删。
     * <p>
     * 不做反向映射存储：映射表需在 createToken/refreshToken/delLoginUser 三处同步写删，TTL 漂移就留孤儿键。
     * 代价是强退从 O(1) 变 O(N) 扫描（与按用户/按角色批量下线同量级），靠“格式先拒 + 命中即停”收敛。
     * 比较用的是我们自己由 uuid 派生的值，入参无法控制被比较的一侧，故不构成可利用的比较侧信道。
     *
     * @param handle 前端回传的脱敏会话标识
     * @return 被清除的会话数（0 表示会话已过期/已重登，handle 失配）
     */
    public int delLoginUserByHandle(String handle) {
        if (handle == null || !SESSION_HANDLE_PATTERN.matcher(handle).matches()) {
            throw new ServiceException("会话标识不合法");
        }
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        int prefixLen = CacheConstants.LOGIN_TOKEN_KEY.length();
        for (String key : keys) {
            if (key == null || key.length() <= prefixLen) {
                continue;
            }
            if (handle.equals(toSessionHandle(key.substring(prefixLen)))) {
                redisCache.deleteObject(key);
                return 1;
            }
        }
        return 0;
    }

    /**
     * 取回当前全部在线会话对象（已剔除过期/不存在的 null）。
     * <p>
     * 对外暴露给在线用户监控页复用，避免 SCAN+MGET 样板在 controller 与 service 两侧各写一次。
     */
    public List<LoginUser> listOnlineLoginUsers() {
        return collectOnlineLoginUsers();
    }

    /**
     * 强制指定用户下线：扫描并删除属于该用户的全部在线会话（含当前会话）。
     * <p>
     * 用于“改密后强制重新登录”等安全策略。token 以会话 uuid 为键、无 user→会话索引，
     * 故只能 SCAN login_tokens:* 反序列化后按 userId 过滤删除（与 refreshPermissionByRoleId 同法）。
     * 删掉缓存后对应 JWT 立即失效（getLoginUser 取不到 loginUser，下次请求即 401）。
     *
     * @param userId 需下线的用户ID
     * @return 被清除的会话数
     */
    public int delLoginUsers(Long userId) {
        if (userId == null) {
            return 0;
        }
        List<LoginUser> loginUsers = collectOnlineLoginUsers();
        int count = 0;
        for (LoginUser loginUser : loginUsers) {
            if (loginUser == null || loginUser.getUser() == null) {
                continue;
            }
            if (userId.equals(loginUser.getUser().getUserId())) {
                // loginUser.getToken() 即该会话的 uuid（createToken/refreshToken 时写入、随对象缓存）
                delLoginUser(loginUser.getToken());
                count++;
            }
        }
        return count;
    }

    /**
     * 创建令牌
     * 
     * @param loginUser 用户信息
     * @return 令牌
     */
    public String createToken(LoginUser loginUser, String ip, String userAgent) {
        String token = IdUtils.fastUUID();
        loginUser.setToken(token);
        setUserAgent(loginUser, ip, userAgent);
        refreshToken(loginUser);

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, token);
        claims.put(Constants.JWT_USERNAME, loginUser.getUsername());
        return createToken(claims);
    }

    /**
     * 验证令牌有效期，相差不足20分钟，自动刷新缓存
     * 
     * @param loginUser 登录信息
     * @return 令牌
     */
    public void verifyToken(LoginUser loginUser) {
        long expireTime = loginUser.getExpireTime();
        long currentTime = System.currentTimeMillis();
        if (expireTime - currentTime <= MILLIS_MINUTE_TWENTY) {
            refreshToken(loginUser);
        }
    }

    /**
     * 刷新令牌有效期
     * 
     * @param loginUser 登录信息
     */
    public void refreshToken(LoginUser loginUser) {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireTime * MILLIS_MINUTE);
        // 根据uuid将loginUser缓存
        String userKey = getTokenKey(loginUser.getToken());
        redisCache.setCacheObject(userKey, loginUser, expireTime, TimeUnit.MINUTES);
    }

    /**
     * 设置用户代理信息
     * 
     * @param loginUser 登录信息
     * @param ip 客户端 IP（由调用方在请求线程抓取后传入，避免异步/内部调用取不到 Request 上下文）
     * @param userAgent 浏览器 UA（同上）
     */
    public void setUserAgent(LoginUser loginUser, String ip, String userAgent) {
        loginUser.setIpaddr(ip);
        loginUser.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        loginUser.setBrowser(UserAgentUtils.getBrowser(userAgent));
        loginUser.setOs(UserAgentUtils.getOperatingSystem(userAgent));
    }

    /**
     * 从数据声明生成令牌
     *
     * @param claims 数据声明
     * @return 令牌
     */
    private String createToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 从令牌中获取数据声明
     *
     * @param token 令牌
     * @return 数据声明
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从令牌中获取用户名
     *
     * @param token 令牌
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    /**
     * 获取请求token
     *
     * @param request
     * @return token
     */
    private String getToken(HttpServletRequest request) {
        String token = request.getHeader(header);
        if (StringUtils.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX)) {
            token = token.replace(Constants.TOKEN_PREFIX, "");
        }
        return token;
    }

    private String getTokenKey(String uuid) {
        return CacheConstants.LOGIN_TOKEN_KEY + uuid;
    }

    /**
     * 角色权限变更后，刷新所有持有该角色的在线用户权限
     *
     * @param roleId            变更的角色ID
     * @param permissionService 权限服务
     */
    public void refreshPermissionByRoleId(Long roleId, SysPermissionService permissionService) {
        List<LoginUser> loginUsers = collectOnlineLoginUsers();
        for (LoginUser loginUser : loginUsers) {
            if (loginUser.getUser() == null || loginUser.getUser().isAdmin()) {
                // 管理员拥有所有权限，跳过
                continue;
            }
            // 判断该用户是否拥有此角色
            boolean hasRole = loginUser.getUser().getRoles() != null
                    && loginUser.getUser().getRoles().stream().anyMatch(r -> roleId.equals(r.getRoleId()));
            if (!hasRole) {
                continue;
            }
            // 刷新权限缓存
            loginUser.setPermissions(permissionService.getMenuPermission(loginUser.getUser()));
            refreshToken(loginUser);
            log.info("角色[{}]权限变更，已刷新在线用户[{}]的权限缓存", roleId, loginUser.getUsername());
        }
    }

    /**
     * 取回全部在线会话：SCAN login_tokens:* 后一次分批 MGET（multiGetObject 已剔除过期/不存在的 null）。
     * 供“按用户/按角色/全量”的权限失效逻辑复用，避免各处重复 SCAN+MGET 样板。
     */
    private List<LoginUser> collectOnlineLoginUsers() {
        String pattern = CacheConstants.LOGIN_TOKEN_KEY + "*";
        Collection<String> keys = redisCache.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        return redisCache.multiGetObject(keys);
    }

    /**
     * 强制持有指定角色的在线用户重新登录：扫描在线会话，凡非管理员且拥有该角色者一并下线。
     * <p>
     * 用于角色停用 / 数据范围变更——二者改变的是缓存 LoginUser 里的角色状态/数据范围快照，
     * 而软刷新(refreshPermissionByRoleId)按缓存角色状态聚合权限、读不到“刚被置停用/新数据范围”，
     * 故必须下线让持有者重建快照，使被收回的权限/新数据范围即刻生效（下次请求即 401）。
     *
     * @param roleId 变更的角色ID
     * @return 被强制下线的会话数
     */
    public int delLoginUsersByRoleId(Long roleId) {
        if (roleId == null) {
            return 0;
        }
        List<LoginUser> loginUsers = collectOnlineLoginUsers();
        int count = 0;
        for (LoginUser loginUser : loginUsers) {
            if (loginUser == null || loginUser.getUser() == null || loginUser.getUser().isAdmin()) {
                // 管理员拥有所有权限，无需因某角色变更而下线
                continue;
            }
            boolean hasRole = loginUser.getUser().getRoles() != null
                    && loginUser.getUser().getRoles().stream().anyMatch(r -> roleId.equals(r.getRoleId()));
            if (!hasRole) {
                continue;
            }
            delLoginUser(loginUser.getToken());
            count++;
        }
        if (count > 0) {
            log.info("角色[{}]停用/数据范围变更，已强制 {} 个持有该角色的在线会话重新登录", roleId, count);
        }
        return count;
    }

    /**
     * 刷新全部在线非管理员用户的权限集合（会话保持、不下线）。
     * <p>
     * 用于菜单编辑——菜单权限字符变化会经 getMenuPermission 逐角色实时查库反映，且不涉及用户角色
     * 成员关系变化，故软刷新即可、无需强制重登。菜单为低频管理操作，全量扫描在线会话可接受。
     */
    public void refreshAllOnlineUserPermission(SysPermissionService permissionService) {
        List<LoginUser> loginUsers = collectOnlineLoginUsers();
        for (LoginUser loginUser : loginUsers) {
            if (loginUser == null || loginUser.getUser() == null || loginUser.getUser().isAdmin()) {
                continue;
            }
            loginUser.setPermissions(permissionService.getMenuPermission(loginUser.getUser()));
            refreshToken(loginUser);
        }
    }
}
