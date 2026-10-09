package com.documenter.filter;

import com.documenter.service.VerificationContext;
import com.documenter.service.VerificationRequestContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把当前请求的来源 IP 与请求 ID 放入验证码上下文（TODO 3.1 风控与排障）。
 *
 * <p>必须在 {@link RequestIdFilter} 之后执行，才能读到已经写入 MDC 的 requestId。
 * 两者都用 OncePerRequestFilter 且明确指定顺序，避免依赖 Bean 扫描顺序。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class VerificationContextFilter extends OncePerRequestFilter {

    /** 反向代理场景下真实客户端 IP 所在的头。 */
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            VerificationRequestContextHolder.set(
                    new VerificationContext(resolveClientIp(request), MDC.get(RequestIdFilter.MDC_KEY)));
            filterChain.doFilter(request, response);
        } finally {
            VerificationRequestContextHolder.clear();
        }
    }

    /**
     * 取客户端 IP。
     *
     * <p>注意：这些头可被客户端伪造，仅用于风控参考，不能作为安全判定的唯一依据。
     * 生产部署必须在可信代理层覆盖这些头。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For 可能是逗号分隔链，第一个是最初的客户端
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
