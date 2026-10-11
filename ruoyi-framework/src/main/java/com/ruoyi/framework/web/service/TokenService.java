package com.ruoyi.framework.web.service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.UserAgentUtils;
import com.ruoyi.common.utils.ip.AddressUtils;
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

    private final RedisCache redisCache;

    // HMAC 签名密钥：构造期一次性生成、final 不可变；密钥不合规在启动即 fail-fast
    private final SecretKey secretKey;

    public TokenService(RedisCache redisCache, @Value("${token.secret}") String secret) {
        this.redisCache = redisCache;
        // HS512 要求密钥不少于 64 字节，过短将在此抛 WeakKeyException 阻断启动
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
