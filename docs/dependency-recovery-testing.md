# 持续读写与依赖中断恢复

2026-10-18 使用本机真实 MariaDB、Redis、回环 HTTP 和隔离临时存储完成以下验证。此项补充此前短时大文件、事务回滚及 JVM 强杀结果，不替代生产容量和跨主机验收。

## 持续读写基线

`ApplicationFlowIntegrationTest.sustainedConcurrentUploadDownloadAndHistoryPreserveEveryVersion` 使用一个随机高位用户，4 个并发客户端各执行 40 轮上传、明确版本下载及历史查询，每轮按 750 ms 节拍运行，持续至少 30 秒。仅在本测试配置中调高上传额度，不改变应用默认值。

首轮记录：

| 项目 | 结果 |
| --- | --- |
| 持续时间 | 30,002 ms |
| 请求 | 480 次，失败 0 |
| 成功上传 | 160 个，每个 787,540 字节 |
| 上传与下载业务字节 | 252,012,800 字节（约 240 MiB，不含协议开销） |
| 上传延迟 p50 / p95 / max | 29 / 38 / 124 ms |
| 下载延迟 p50 / p95 / max | 11 / 17 / 36 ms |
| 历史查询 p50 / p95 / max | 4 / 10 / 18 ms |
| 一致性 | 160 个文件对应 160 个初始版本和 160 个磁盘文件，下载逐字节一致，数据库摘要一致，无孤儿文件 |

每次生成 `target/sustained-load-report.json`，重跑后的观察值以新报告为准。这是有节拍的本机持续基线，不是饱和压测，也未测量生产带宽、峰值内存或长期稳定性。

## 数据库写入连接被断开

`DatabaseLifecycleIntegrationTest.disconnectedWriterRollsBackAndRetryProducesExactlyOneVersion` 在真实版本 SQL 已执行但外层事务尚未提交时，记录该事务的 `CONNECTION_ID()`。父测试先通过 `information_schema.PROCESSLIST` 核对连接确实属于本轮随机测试库，再仅对该连接执行 `KILL CONNECTION`，不停止数据库服务。

断连后事务失败并回滚，原版本及 latest_version 保持 1。随后使用相同任务/尝试标识重试并重放：只产出一个版本 2，数据库引用和磁盘内容 SHA-256 一致。该项模拟连接丢失，不是数据库服务器崩溃或断电。

## 独立 Redis 故障与恢复

`ApplicationFlowIntegrationTest.isolatedRedisOutageFailsClosedAndRecoversWithoutRevivingTokens` 使用 `DOCUMENTER_TEST_REDIS_SERVER` 指定的本机 Redis 可执行文件，创建随机回环端口、独立配置和目录，只停止本测试创建的进程，不中断用户 6379 服务。

- 服务正常时可以登录并访问受保护接口。
- 强杀临时 Redis 后，受保护接口和登录返回明确的 JSON 503；上传不能产生文件或数据库记录。
- 使用同一端口重新启动空 Redis，应用自动重新连接，新登录与文件上传/下载恢复。
- 测试 Redis 禁用持久化，重启后旧会话丢失，旧 Access/Refresh Token 保持无效，不被新登录复活。

实测发现 Redis 中断时，认证异常经过容器错误分派后会被错误表现为 401。修复认证过滤器显式识别依赖中断、清空认证上下文并直接返回 503；公开登录等接口由统一异常处理返回 503。数据库连接错误 SQLState `08` 同样返回 503；数据库写入冲突仍返回 409。认证过滤链和异常响应单测补充了响应码及内部连接信息不泄露检查。

## 复现及边界

沿用 [完整应用验收](application-flow-testing.md) 的本机数据库/Redis 环境变量，启用 `DOCUMENTER_APP_TEST_ENABLED=true`、`DOCUMENTER_LOAD_TEST_ENABLED=true`、`DOCUMENTER_LIFECYCLE_DB_TEST_ENABLED=true`。临时 Redis 用例还需设置 `DOCUMENTER_TEST_REDIS_SERVER` 为本机 redis-server 可执行文件路径。

```powershell
mvn -o test '-Dtest=ApplicationFlowIntegrationTest#sustainedConcurrentUploadDownloadAndHistoryPreserveEveryVersion+isolatedRedisOutageFailsClosedAndRecoversWithoutRevivingTokens,DatabaseLifecycleIntegrationTest#disconnectedWriterRollsBackAndRetryProducesExactlyOneVersion,SecurityFilterChainTest,DatabaseConflictResponseTest'
```

测试清理随机隔离库、目录和该用户的 Redis 键。尚需生产环境验证：实际业务持续负载、数据库服务器故障/断电、Redis AOF/RDB 持久化恢复、跨主机网络分区、共享存储和真实外部服务故障。

后续已补本机 AOF/RDB 强杀恢复、TTL/额度核对、旧快照会话清理脚本和存储目录不可用验收，见 [持久化与存储故障](redis-persistence-testing.md)。生产配置和主机断电等场景仍需对应环境。

## 本轮回归

后端回归选择 831 项：830 通过、0 失败/错误，1 项为未重跑的 HTTP Office 用例。未修改的独立 Office 测试组沿用上一轮结果，不计入本轮通过数。`mvn -o package` 成功，新 JAR 另行执行的独立启动/强杀重启与租约恢复两项验收均通过，合计本轮 832 项通过。完整 HTTP 组中启用的 14 项场景均通过，持续负载第二轮仍为 480 请求、160 文件、0 失败。验收结束无残留随机测试库或临时 Redis 子进程，原 6379 服务保持正常。
