package com.documenter.configuration;

import com.documenter.filter.JwtAuthenticationFilter;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig {
	
	@Resource
	private JwtAuthenticationFilter jwtAuthenticationFilter;
	
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
		http.exceptionHandling(exceptionHandling -> exceptionHandling.authenticationEntryPoint(loginUnAuthenticationEntryPointHandler));
		// 注册自定义的处理器 (认证后的用户访问需要认证资源时因为权限不足走的处理器)
		http.exceptionHandling(exceptionHandling -> exceptionHandling.accessDeniedHandler(loginUnAccessDeniedHandler));
		// 自定义登出成功处理器
		http.logout(logout -> logout.logoutUrl("/user/logout").logoutSuccessHandler(logoutStatusSuccessHandler));
		return http.build();
	}
}
