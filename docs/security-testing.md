# 安全回归与 Redis 集成测试

普通测试执行 `mvn -o test`。其中 SecurityFilterChainTest 使用真实 JWT 签名和校验器、应用的 SecurityConfig 与完整 Spring Security 过滤链；只替换 Redis 和用户 Mapper。验证码发布测试验证 Java 服务使用原子脚本及失败处理，不将 Mockito 返回值当作 Lua 已在 Redis 执行的证据。

RedisSecurityIntegrationTest 需要明确设置 DOCUMENTER_TEST_REDIS_PORT；未设置时，7 项用例跳过。设置后只连接 127.0.0.1 的指定端口，不读取应用 Redis 地址、密码或数据库配置；连接失败会让测试失败，不会静默跳过。请使用专用、无密码的临时测试 Redis，不能指向生产服务。

该测试类执行实际 Redis Lua，覆盖以下场景：

- 验证码摘要、错误计数和 TTL 一次发布，成功后不能重放。
- 达到最大错误次数的当次立即返回 429 并删除，之后正确验证码也不能通过。
- 24 次并发消费只有一次成功。
- 注册、登录、找回密码用途隔离，过期验证码被拒绝。
- 重新发送时摘要、计数和 TTL 同时更新。
- 24 次并发刷新旧 Refresh Token 只有一次成功，退出后 Access/Refresh 均不可用。
- 24 次并发申请 AI 日额度，在限额为 5 时只允许 5 次。

测试使用随机对象标识及随机用户 ID，结束时只清理自己创建的键，不执行 FLUSHDB/FLUSHALL，不发送真实短信，也不连接数据库。随机测试接收对象直接传给服务层，不经过 HTTP 手机号 DTO 校验；验证的目标是 Redis 原子行为。

使用 Docker 的 PowerShell 示例（需要先启动 Docker 引擎）：

```powershell
docker run --rm -d --name documenter-test-redis -p 127.0.0.1:16379:6379 redis:7-alpine
if ($LASTEXITCODE -ne 0) { throw '测试 Redis 未创建成功' }
$previousRedisTestPort = $env:DOCUMENTER_TEST_REDIS_PORT
try {
    $env:DOCUMENTER_TEST_REDIS_PORT = '16379'
    mvn -o '-Dtest=RedisSecurityIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Redis 集成测试未通过' }
} finally {
    $env:DOCUMENTER_TEST_REDIS_PORT = $previousRedisTestPort
    docker stop documenter-test-redis
}
```

Docker 不可用时，可以自行启动等效的临时本机 Redis，再设置端口运行同一 Maven 命令；无需启动整个应用。不要为运行测试修改生产配置。

2026-10-17 本机验证情况：JWT 完整过滤链及验证码 Java 服务回归已运行；Docker 引擎未启动，真实 Redis 集成用例未执行。该测试类虽已编译，仍需在专用 Redis 上运行并确认结果。真实数据库归属、行锁、迁移、外部短信和跨实例恢复继续保持未验收。
