package com.documenter.configuration;

import com.documenter.constant.ApiResponse;
import com.documenter.entity.User;
import com.documenter.filter.JwtAuthenticationFilter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig {
	
	@Resource
	private JwtAuthenticationFilter jwtAuthenticationFilter;
	@Resource
	private ObjectMapper mapper;
	@Resource
	private RedisTemplate<String,Object> redisTemplate;
	
	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		
		http.csrf(AbstractHttpConfigurer::disable) // 防止跨站请求伪造
				.sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 取消 session
				.authorizeHttpRequests(authorizeRequests -> authorizeRequests
								.requestMatchers("/user/login").permitAll()
				);
		// 自定义每次请求的过滤器
		http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		// 自定义未认证处理器
		http.exceptionHandling(exceptionHandling -> exceptionHandling.authenticationEntryPoint((request, response, authException) -> {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json;charset=UTF-8");
			ApiResponse<String> apiResponse=ApiResponse.error(401,"未授权访问");
			String jsonResponse=mapper.writeValueAsString(apiResponse);
			response.getWriter().write(jsonResponse);
		}));
		// 注册自定义的处理器 (认证后的用户访问需要认证资源时因为权限不足走的处理器)
		http.exceptionHandling(exceptionHandling -> exceptionHandling.accessDeniedHandler((request, response, accessDeniedException) -> {
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json;charset=UTF-8");
			ApiResponse<Object> result = ApiResponse.error(403, "用户权限不足，无法访问此资源");
			// 将消息 json 化
			String json = mapper.writeValueAsString(result);
			// 送到客户端
			response.getWriter().print(json);
		}));
		// 自定义登出成功处理器
		http.logout(logout -> logout.logoutUrl("/user/logout").logoutSuccessHandler((request, response, authentication) -> {
			User user = null;
			if (authentication != null) {
				user = (User) authentication.getPrincipal();
			}
			response.setStatus(200);
			String authorization = request.getHeader("authorization");
			if (authorization != null) {
				redisTemplate.delete("login:token:" + authorization);
			}
			if (user != null && user.getId() != null) {
				redisTemplate.delete("user:openid:" + user.getId());
			}
			SecurityContextHolder.clearContext();
			response.setContentType("application/json;charset=UTF-8");
			ObjectMapper mapper = new ObjectMapper();
			ApiResponse<String> apiResponse=ApiResponse.success("登出成功");
			String jsonResponse=mapper.writeValueAsString(apiResponse);
			response.getWriter().write(jsonResponse);
		}));
		return http.build();
	}
}
