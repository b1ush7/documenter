package com.documenter.filter;

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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求建立可追踪的请求 ID（TODO 3.4「补齐请求 ID 和基础运行日志」）。
 *
 * <p>行为：优先复用调用方传入的 {@code X-Request-ID}，否则生成一个；
 * 写入 MDC 供日志输出，并回写到响应头，便于前端报错时带上该 ID 排查。
 *
 * <p>安全：调用方传入的值会被白名单校验。若直接回写未校验的头，
 * 攻击者可用 CRLF 注入额外响应头或污染日志，因此非法值一律丢弃并重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    /** 只允许常见的 ID 字符集，且长度受限，防止头注入与日志污染。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程会被容器复用，必须清理，否则下一个请求会带上错误的 requestId
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
