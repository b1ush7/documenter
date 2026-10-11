package com.documenter;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * 集成测试的外部依赖探测（测试辅助，不属于产品代码）。
 *
 * <p>存在的理由：{@code @SpringBootTest} 会真实加载 Spring 上下文，
 * 而本项目启动时强依赖 MariaDB 与 Redis（JwtUtil 需要 Redis、MyBatis 需要 DataSource、
 * Flyway 需要能连上数据库）。如果开发机上这两个服务没起，测试会直接失败，
 * 让人误以为是代码有问题。
 *
 * <p>因此集成测试先探测端口，不可用就跳过，并打印明确原因，
 * 而不是抛出一堆看不懂的 Bean 创建异常。
 */
public final class IntegrationEnvironment {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private IntegrationEnvironment() {
    }

    /** 数据库与 Redis 是否都可达。 */
    public static boolean available() {
        return reachable(dbHost(), dbPort()) && reachable(redisHost(), redisPort());
    }

    /** 供 skip 提示使用的可读原因。 */
    public static String unavailableReason() {
        StringBuilder reason = new StringBuilder();
        if (!reachable(dbHost(), dbPort())) {
            reason.append("MariaDB(").append(dbHost()).append(':').append(dbPort()).append(") 不可达; ");
        }
        if (!reachable(redisHost(), redisPort())) {
            reason.append("Redis(").append(redisHost()).append(':').append(redisPort()).append(") 不可达; ");
        }
        return reason.isEmpty() ? "" : reason.toString();
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String dbHost() {
        return env("DB_HOST", "localhost");
    }

    private static int dbPort() {
        return intEnv("DB_PORT", 3306);
    }

    private static String redisHost() {
        return env("REDIS_HOST", "localhost");
    }

    private static int redisPort() {
        return intEnv("REDIS_PORT", 6379);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        try {
            return Integer.parseInt(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
