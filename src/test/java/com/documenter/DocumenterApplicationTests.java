package com.documenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文加载测试。
 *
 * <p>这是项目里唯一的真正端到端冒烟测试：它会真实启动 Spring 上下文，
 * 因此会触发 Flyway 迁移、MyBatis-Plus 装配、Redis 连接与安全配置。
 *
 * <p>启动强依赖 MariaDB 与 Redis。开发机上没起这两个服务时，测试会失败并抛出
 * 一长串 Bean 创建异常，容易被误判成代码缺陷，所以这里用 {@link EnabledIf}
 * 先探测端口：不可用则跳过，并在日志里说明原因。
 *
 * <p>注意：跳过不等于通过。这只保证「环境没准备好时不会误报」，
 * 真正的验收仍然必须在 MariaDB 与 Redis 都启动的情况下跑一次。
 */
@SpringBootTest
@EnabledIf(value = "com.documenter.IntegrationEnvironment#available",
        disabledReason = "MariaDB / Redis 未启动，跳过上下文加载测试")
class DocumenterApplicationTests {

    @Test
    void contextLoads() {
        // 能走到这里说明 Flyway 迁移、MyBatis-Plus 装配、Redis 连接与安全配置都成功初始化
    }
}
