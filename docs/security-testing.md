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

2026-10-17 本机验证情况：JWT 完整过滤链及验证码 Java 服务回归已运行；使用用户提供的本地 `127.0.0.1:6379`、默认用户且无密码连接，`RedisSecurityIntegrationTest` 的 7 项真实 Redis 用例全部通过，无失败、错误或跳过。验证包括验证码 TTL/错误上限/用途隔离/原子消费、24 路 Refresh Token 轮换与退出撤销，以及 24 路 AI 日额度竞争。测试仅写入随机标识的测试键，结束时清理自身键，未执行 FLUSHDB/FLUSHALL，也未发送真实短信。

本地 MariaDB 13.0.2 的 V8 迁移与回收 SQL/隔离磁盘流程另见 [正式存储回收](storage-recycling.md)。带旧数据的 V1→V9 完整升级、真实文档版本服务事务/16 路并发，以及 mariadb-dump 与文件存储配套恢复也已在隔离库通过，见 [数据库生命周期验收](database-lifecycle-testing.md)。执行租约和双实例失联恢复见 [租约验收](execution-leases.md)。已有业务数据副本、其他业务事务及跨主机部署恢复仍未验收；真实短信和云 OCR 按当前范围暂缓。
