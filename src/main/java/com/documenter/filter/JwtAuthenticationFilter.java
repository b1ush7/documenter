package com.documenter.filter;

import com.documenter.configuration.JwtProperties;
import com.documenter.entity.User;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.UserMapper;
import com.documenter.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final JwtProperties properties;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserMapper userMapper, JwtProperties properties) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.properties = properties;
    }

    /** 免认证路径，必须与 SecurityConfig 的 permitAll 列表保持一致。 */
    private static final java.util.Set<String> PUBLIC_PATHS = java.util.Set.of(
            "/user/register",
            "/user/register/by-code",
            "/user/register/code",
            "/user/login",
            "/user/login/by-code",
            "/user/login/code",
            "/user/verify/code",
            "/user/refresh");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 免认证接口：携带过期或无效的 Access Token 时不应被过滤器拦截成 401
        return PUBLIC_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = getAccessToken(request);
        if (token != null && !token.isBlank()) {
            try {
                String userId = jwtUtil.getAuthenticatedUserId(token);
                User user = userMapper.selectById(Long.valueOf(userId));
                if (user == null) {
                    throw new BusinessException(401, "未授权访问");
                }
                List<SimpleGrantedAuthority> authorities = "ADMIN".equals(user.getRole())
                        ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))
                        : List.of(new SimpleGrantedAuthority("ROLE_USER"));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, authorities));
            } catch (BusinessException | NumberFormatException e) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"Token 无效或已过期\"}");
                return;
            }
        }
        // 访问请求不延长任何 Token 的有效期；续期只能通过 Refresh Token 轮换。
        filterChain.doFilter(request, response);
    }

    public String getAccessToken(HttpServletRequest request) {
        String header = request.getHeader(properties.getAuthorizationName());
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return header;
    }
}
