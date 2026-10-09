package com.documenter.configuration;

import com.documenter.constant.ApiResponse;
import com.documenter.filter.JwtAuthenticationFilter;
import com.documenter.util.JwtUtil;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtUtil jwtUtil;
    private final ObjectMapper mapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, JwtUtil jwtUtil, ObjectMapper mapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtUtil = jwtUtil;
        this.mapper = mapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/user/login").permitAll()
                        .anyRequest().authenticated());
        // 必须先认证再登出，确保登出能够撤销当前 JWT 会话。
        http.addFilterBefore(jwtAuthenticationFilter, LogoutFilter.class);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(mapper.writeValueAsString(ApiResponse.error(401, "未授权访问")));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(mapper.writeValueAsString(
                            ApiResponse.error(403, "用户权限不足，无法访问此资源")));
                }));
        http.logout(logout -> logout.logoutUrl("/user/logout")
                .addLogoutHandler((request, response, authentication) -> {
                    String token = jwtAuthenticationFilter.getAccessToken(request);
                    if (token != null && !token.isBlank()) {
                        jwtUtil.revokeTokens(token);
                    }
                })
                .logoutSuccessHandler((request, response, authentication) -> {
                    SecurityContextHolder.clearContext();
                    response.setStatus(200);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(mapper.writeValueAsString(ApiResponse.success("登出成功")));
                }));
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration() {
        // JWT 过滤器只在 SecurityFilterChain 中运行，避免被 Servlet 容器提前执行。
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }
}
