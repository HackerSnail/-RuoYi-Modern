package com.ruoyi.framework.security.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;

/**
 * token过滤器 验证token有效性
 * 
 * @author ruoyi
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {
    private final TokenService tokenService;

    public JwtAuthenticationTokenFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * 每请求执行一次：从 token 解析登录用户，仅当解析成功且当前尚未认证时，
     * 顺便刷新 token 有效期并将认证信息写入 SecurityContext（已认证则不覆盖）
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // token 非法(格式/签名)或缓存读取异常已由 TokenService.getLoginUser 内部 try-catch 吞掉并返回 null，
        // 且本仓 JWT 未设 exp、会话有效期由 Redis key TTL 决定(过期即 key 不存在→返 null)，故此处无需再包异常：
        // loginUser==null 即跳过、直接放行，交后续 @PreAuthorize / AuthenticationEntryPoint 统一返回 401
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication())) {
            tokenService.verifyToken(loginUser);
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }
        chain.doFilter(request, response);
    }
}
