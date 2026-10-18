# Redis 持久化、旧快照与存储故障验收

2026-10-18 在独立临时 Redis、随机回环端口、隔离 MariaDB 库和存储目录执行。未修改用户 6379 配置，也未在该实例执行会话清理。

## 新增验证

| 场景 | 验证结果 |
| --- | --- |
| AOF：`appendonly yes`、`appendfsync always`，强杀后原目录重启 | 有效会话可继续访问，已退出会话和已轮换的旧 Refresh Token 仍无效；上传/AI 日额度及会话索引保留；原 TTL 大于 0 且不延长；文件下载摘要一致 |
| RDB：显式 `SAVE` 完成后强杀并重启 | 保存快照中的上述状态、额度及 TTL 均恢复；不承诺快照后写入持久化 |
| 旧 RDB：先保存快照，再退出登录，随后强杀 | 应用保持停止，确认旧会话记录被快照带回；清理恢复会话后再启动，旧 Access/Refresh Token 均无效，新登录及原文件下载正常 |
| 存储目录不可用 | 仅在隔离目录中将存储根目录移开并放入同名普通文件，真实上传失败；数据库和版本数不增长，原文件摘要不变；恢复目录后原文件可下载，新上传正常，无孤儿文件 |

用例位于 `ApplicationFlowIntegrationTest`：参数化的 `redisPersistencePreservesSessionsRevocationsAndQuotaAfterForcedRestart`（AOF/RDB 两项）、`staleRdbRecoveryInvalidatesRestoredSessionsBeforeApplicationStarts` 和 `unavailableStorageDirectoryLeavesExistingVersionUntouchedAndRecovers`，共 4 项。

## 旧快照恢复流程

旧快照可能包含快照之后才撤销的会话，这一现象已在测试中实际复现。恢复期间停止所有应用实例，恢复 Redis 后先清理登录会话，再启动应用。准备了 `scripts/invalidate-restored-sessions.ps1`：

```powershell
# 填入此次恢复目标的 redis-cli 路径和端口。以下两步均须在应用停服期间执行。
./scripts/invalidate-restored-sessions.ps1 -RedisCli 'C:/redis/redis-cli.exe' -Port 16379
./scripts/invalidate-restored-sessions.ps1 -RedisCli 'C:/redis/redis-cli.exe' -Port 16379 -Apply
```

第一步默认预演，仅返回匹配数量；第二步显式执行。脚本分批 SCAN，只删除项目当前格式的 `login:session:<UUID>` 与 `login:user_sessions:<userId>`，不清库，不删除上传额度、AI 额度、验证码、缓存或文档。需要密码时由 `REDISCLI_AUTH` 环境变量提供，不放入命令行。可用 `-RedisHost`、`-Database` 指定目标，执行前核对目标。

实跑检查预演不修改键、执行清理后旧令牌失效、额度和 TTL 保留、重复执行删除 0 个键；Redis 缺少 SCAN 权限时脚本失败退出。脚本不会替用户停止应用，实例停服是操作前提；不能在旧会话已被恢复且应用仍接流量时才启动清理。

## 复现与边界

沿用完整 HTTP 用例的数据库及 Redis 测试环境变量，另外设置：

```powershell
$env:DOCUMENTER_TEST_REDIS_SERVER = 'C:/redis/redis-server.exe'
$env:DOCUMENTER_TEST_POWERSHELL = (Get-Command pwsh).Source
mvn -o test '-Dtest=ApplicationFlowIntegrationTest#redisPersistencePreservesSessionsRevocationsAndQuotaAfterForcedRestart+staleRdbRecoveryInvalidatesRestoredSessionsBeforeApplicationStarts+unavailableStorageDirectoryLeavesExistingVersionUntouchedAndRecovers'
```

旧快照脚本用例为 Windows/PowerShell 专项，使用同目录 redis-cli。脚本执行从测试中启动，整个清理只针对测试自建的临时 Redis。

4 项验收通过并完成 Maven 打包；脚本增加回复格式校验及权限失败用例后，旧快照恢复用例定向复核也通过。此次只增加测试、运维脚本和文档，未修改后端运行逻辑，沿用上一轮后端回归与 JAR 验收结果。

本地进程强杀不等同于主机断电。RDB 仅恢复快照保存时的状态，清理登录会话不会补回快照之后丢失的额度或验证码状态；生产 Redis 持久化配置、可接受的数据丢失窗口、备份副本和恢复流程仍需在实际部署环境确认。本次未模拟实际磁盘写满或损坏，只验证目录不可用时的失败与恢复。
