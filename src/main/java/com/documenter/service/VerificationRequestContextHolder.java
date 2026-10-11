package com.documenter.service;

/**
 * 当前请求的验证码上下文持有者。
 *
 * <p>来源 IP 与请求 ID 由 Web 层写入、业务层读取，避免业务层直接依赖 HttpServletRequest，
 * 也避免为了传递两个字符串而污染 {@link VerificationCodeService} 的方法签名。
 *
 * <p>使用 ThreadLocal 时必须清理：Servlet 线程会被复用，残留值会让下一个请求
 * 记录到错误的来源信息。清理动作放在 {@code VerificationContextFilter} 的 finally 中。
 */
public final class VerificationRequestContextHolder {

    private static final ThreadLocal<VerificationContext> HOLDER = new ThreadLocal<>();

    private VerificationRequestContextHolder() {
    }

    public static void set(VerificationContext context) {
        HOLDER.set(context);
    }

    public static VerificationContext get() {
        VerificationContext context = HOLDER.get();
        return context == null ? VerificationContext.empty() : context;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
