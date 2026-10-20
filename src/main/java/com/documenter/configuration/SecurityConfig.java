package com.documenter.configuration;

import com.documenter.constant.ApiResponse;
import com.documenter.filter.JwtAuthenticationFilter;
import com.documenter.util.JwtUtil;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CorsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

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
    public CorsConfigurationSource configurationSource(){
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("https://jenandygyt-web.github.io"
                , "http://localhost:5173"));
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod(HttpMethod.GET);
        configuration.addAllowedMethod(HttpMethod.POST);
        configuration.addAllowedMethod(HttpMethod.PUT);
        configuration.addAllowedMethod(HttpMethod.DELETE);
        configuration.addAllowedMethod(HttpMethod.OPTIONS);
        configuration.setAllowedHeaders(List.of("*"));
        // 跨域时自定义响应头默认对前端不可见，必须显式暴露：
        // 预览接口用它回传图片尺寸与总页数（DOCX/XLSX 的页数只有渲染后才确定）
        configuration.setExposedHeaders(List.of(
                "X-Image-Width", "X-Image-Height", "X-Total-Pages", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // register 系列必须写在 logout 之前，否则 /user/register/by-code 会被通配规则干扰
                        .requestMatchers("/user/register", "/user/register/by-code", "/user/register/code").permitAll()
                        .requestMatchers("/user/login", "/user/login/by-code", "/user/login/code").permitAll()
                        .requestMatchers("/user/verify/code").permitAll()
                        // 找回密码：用户此时必然没有登录态，必须放行
                        .requestMatchers("/user/reset-password", "/user/reset-password/code").permitAll()
                        .requestMatchers("/user/refresh").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // 修改密码需要登录态（要校验原密码），因此不放进白名单
                        // logout-all 是业务接口，必须显式声明，避免被 logout 相关规则影响
                        .requestMatchers("/user/logout-all").authenticated()
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
        http.cors(cors->cors.configurationSource(configurationSource()));
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
