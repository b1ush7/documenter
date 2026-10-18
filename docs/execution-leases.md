# 多实例执行租约与失联恢复

V9 为任务和 AI 回复增加 `execution_owner`、`lease_until` 及过期扫描索引。功能默认关闭，单实例配置继续使用原有重启恢复。多实例启用后，启动恢复器自动跳过全表 RUNNING 收尾，改由各实例的租约维护器定时扫描；全部参与实例必须运行兼容版本、使用相同模式、共享 MariaDB、Redis 和一致的存储内容。

```text
TASK_CLUSTER_ENABLED=true
TASK_CLUSTER_HEARTBEAT=10s
TASK_CLUSTER_LEASE_DURATION=60s
TASK_CLUSTER_BATCH_SIZE=100
TASK_CLUSTER_REQUEUE_PENDING=true
```

每次进程启动生成新的随机实例标识。任务通过 PENDING 条件更新取得唯一执行权；AI 回复在预留事务中绑定实例和有效期。每个实例仅为本机仍在执行的任务/回复续期，工作结束或异常退出就停止追踪。有效期和过期判断统一使用 MariaDB 的 `NOW(3)`，不依赖各主机时钟一致。已经过期的租约不能续回。

默认每 10 秒续期并扫描，每批最多 100 项；heartbeat 至少 100 ms，lease-duration 至少为 heartbeat 的三倍且最多一小时，batch-size 为 1～1000。应让租期覆盖正常数据库延迟、锁等待和 JVM 暂停；不要照抄集成测试使用的 3 秒租期作为生产配置。数据库无法访问时不猜测任务状态，也不能绕过数据库完成持久化写入。

## 恢复和写入隔离

- 只处理有实例归属且已经过期的 RUNNING 记录。使用任务/回复行锁和 `SKIP LOCKED` 分批扫描，多个恢复器可同时工作。
- 过期任务和运行中步骤在同一事务内变为 FAILED，任务错误码为 `LEASE_EXPIRED`。过期回复变为 FAILED/NONE，不保留待确认计划或迟到结果。
- 运行中任务不自动重跑。用户通过现有重试入口产生新任务 ID；AI 回复使用新的幂等键重新发送，避免重放外部调用。已经提交的版本继续保留，恢复不会删除历史版本。
- 历史 PENDING 可由维护器持续重新入队；调度池拒绝时仍保留 PENDING，下一轮再尝试。是否排空由 `TASK_CLUSTER_REQUEUE_PENDING` 控制。
- 步骤、进度、输出版本及终态更新先在事务内检查归属和有效租约，并持有任务行锁。文档追加还校验任务的用户、文件、运行状态及取消标记，在版本提交前再次检查租约；旧执行者和已过期事务不能补写版本。
- AI 回复保存同样在短事务内检查实例和租约，提交前再验证归属/有效期。模型迟到返回不会覆盖恢复后的失败记录。
- 心跳先跳过持锁记录，再用新的数据库语句检查有效期；提交前锁定查询显式绕过 MyBatis 会话缓存，避免等待前的时间或幂等快路径的缓存结果放过过期执行者。
- PDF 转换的新文件若数据库事务回滚，使用事务完成回调清理。中断/断电造成的未索引文件仍由已有正式回收宽限规则处理。

## 模式切换

先备份数据库和正式文件，并停止所有旧执行者，再应用 V9 和统一设置后启动。不要混用未启用租约的旧实例与启用租约的新实例。单实例启动恢复已限制为无归属记录，避免误结束有效租约，但这不构成混合版本部署支持。

V9 不猜测旧记录属于哪个进程。无实例归属的历史 RUNNING 记录不会被租约扫描器自动结束；切换前在确认旧实例全部停止后，用原单实例恢复流程收尾，或人工审查这些记录。关闭 cluster 前也应先停止执行并处理遗留租约记录。所有节点的 JWT 密钥、模型配置、存储路径及数据库策略仍需一致；租约不能替代共享文件系统、Redis 或备份。

## 本机验收

2026-10-17 已在本机 MariaDB 13.0.2 / Redis 6379 执行以下应用上下文及双 JAR 进程测试，并通过完整后端回归。所有数据均为本轮随机隔离库、临时存储和随机大整数用户的 Redis 键。

`ApplicationFlowIntegrationTest#expiredInstanceIsRecoveredAndItsLateTaskAndModelResultCannotWrite` 启动两个独立应用上下文、真实 MariaDB/Redis 和本机模型 HTTP 夹具，使用 200 ms 心跳、3 秒租期验证：

1. 任务与真实回复请求阻塞超过初始租期仍可续期；第二实例启动不结束活跃工作。
2. 停止 A 的心跳后，B 结束过期任务、步骤和回复，自己的有效任务仍运行；无归属历史任务保持不变。
3. 模拟不响应中断的旧任务恢复后调用真实 PDF 转 DOCX，追加被拒绝且新文件被清理；迟到模型结果返回 409，原回复保持 FAILED/NONE。
4. 另一个实例冒用有效任务 ID 写版本被拒绝；外层事务在追加后、提交前让租约失效，版本号和记录整体回滚。
5. 健康实例的新回复和持久化 PENDING 转换正常完成；两个恢复器以小批量并发处理过期任务，保留有效租约。

另外，`PackagedClusterRecoveryIntegrationTest` 面向两个独立可执行 JAR 进程：真实请求在本机模型夹具中阻塞，强杀拥有回复的进程，存活进程根据过期租约收尾并继续处理后续回复。执行前必须先打包当前源码：

```powershell
# 数据库与 Redis 测试环境变量按 application-flow-testing.md 配置，仅允许本机随机测试库。
$env:DOCUMENTER_APP_TEST_ENABLED = 'true'
$env:DOCUMENTER_CLUSTER_TEST_ENABLED = 'true'
mvn -o test '-Dtest=ApplicationFlowIntegrationTest'
mvn -o package
$env:DOCUMENTER_PACKAGED_TEST_JAR = Join-Path $PWD 'target/documenter-0.0.1-SNAPSHOT.jar'
$env:DOCUMENTER_PACKAGED_CLUSTER_TEST_ENABLED = 'true'
mvn -o test '-Dtest=PackagedClusterRecoveryIntegrationTest'
```

测试只访问本机模型夹具，不代表远程模型质量或真实网络分区验收。跨主机共享存储、数据库故障转移、长期网络隔离与生产负载仍需在部署环境验收。
