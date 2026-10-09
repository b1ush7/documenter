package com.documenter.filter;

import com.documenter.entity.User;
import com.documenter.mapper.UserMapper;
import com.documenter.util.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	@Resource
	private RedisTemplate<String,Object> redisTemplate;
	@Resource
	private JwtUtil jwtUtil;
	@Resource
	private UserMapper userMapper;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
		String requestURI = request.getRequestURI();
		String method = request.getMethod();
		if(requestURI.endsWith("/user/login")||
				requestURI.endsWith("/user/register")||
				requestURI.endsWith("/user/quickLogin")||
				// endsWith 是字面匹配，"/product/*" 永远不命中；公开浏览接口按 SecurityConfig 一致地只放行 GET
				(requestURI.startsWith("/api/product/") && "GET".equals(method))||
				requestURI.endsWith("/pay/notify")||
				requestURI.endsWith("/balance/notify")||
				requestURI.endsWith("/logistics/callback")
		){
			filterChain.doFilter(request,response);
			return;
		}
		String token=request.getHeader("authorization");
		try {
			if(token!=null&&!token.isEmpty()) {
				if(jwtUtil.validateToken(token)) {
					String tokenKey = "login:token:" + token;
					User user = resolveUser(redisTemplate.opsForValue().get(tokenKey));
					if (user != null) {
						if(user.getRole().equals("USER")){
							redisTemplate.expire(tokenKey,7, TimeUnit.DAYS);
							redisTemplate.expire("user:openid"+user.getId(),7,TimeUnit.DAYS);
						}
						//                    String openid=user.getOpenid();
						List<SimpleGrantedAuthority> authorities = user.getRole().equals("ADMIN")
								? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))
								: List.of(new SimpleGrantedAuthority("ROLE_USER"));
						UsernamePasswordAuthenticationToken authenticationToken =
								new UsernamePasswordAuthenticationToken(user, null, authorities);
						SecurityContextHolder.getContext().setAuthentication(authenticationToken);
					}else {
						response.setStatus(401);
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":401,\"msg\":\"未授权访问\"}");
						return;
					}
				}
			}
		} catch (Exception e) {
			response.setStatus(401);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":401,\"msg\":\"未授权访问\"}");
			return;
		}
		filterChain.doFilter(request,response);
	}

	private User resolveUser(Object cached) {
		Long userId = null;
		if (cached instanceof Number number) {
			userId = number.longValue();
		}
		return userId == null ? null : userMapper.selectById(userId);
	}
}
