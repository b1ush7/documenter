# 业务事务与写入中断验收

2026-10-18 使用本机真实 MariaDB、Redis、随机隔离数据库和独立存储目录验证。验证码仍为 Mock，远程 OCR 关闭，AI 确认使用本机确定性 stub，不发送外部模型请求。

## 覆盖内容

- 模板：在历史快照写入时用数据库触发器注入失败，创建不留下元数据/文件，替换不改变原版本/存储引用；外层事务回滚同样清理候选文件。
- 模型配置：新默认标记写入失败后恢复旧默认；8 路切换竞争后仍只有一个默认。MariaDB 1020、1205、1213 及事务序列化冲突向客户端返回 409，不输出 SQL 细节。
- 上传与生成：方法返回之后、事务提交之前注入异常，文件记录和初始版本一起回滚，磁盘候选文件同步清理。
- 本地人工校正：首次插入和已有校正更新都可回滚，不新增文档版本，不调用 OCR 服务。
- 账号：禁用、提权、改密后强制外层事务回滚，原状态、角色和密码不变；Redis 会话撤销不会随数据库回滚恢复，旧凭据保持失效，使用原密码可以重新登录。
- AI 确认：在确认状态落库时注入失败，文档版本、文件、计划状态一起回滚；四路重复确认只追加一个版本，使用确定性模型夹具。

新增实库用例位于 `ApplicationFlowIntegrationTest`，HTTP 测试环境变量与 [application-flow-testing.md](application-flow-testing.md) 相同；方法名以 `templateHistoryFailure`、`defaultModelSwitchFailure`、`uploadAndGenerationClean`、`localOcrCorrection`、`accountMutationRollback`、`aiConfirmationDatabaseFailure` 开头。

## 独立 JVM 强杀

`DatabaseLifecycleIntegrationTest.forcedJvmDeathAtWriteAndCommitBoundariesPreservesAtomicVersions` 启动测试专用 `CrashWriteProbe` 子 JVM，使用生产 `LocalFileStorage`、MyBatis Mapper、`DocumentVersionServiceImpl` 和真实 Spring 事务代理，在以下三个位置发出文件屏障并阻塞，然后由父测试强杀：

| 位置 | 预期结果 |
| --- | --- |
| 输入流写出首块后，文件尚未写完 | 没有新版本或新数据库引用，原文件摘要不变 |
| 已执行版本 SQL、进入 beforeCommit 回调 | 未提交事务回滚，版本计数和 latest_version 保持 1 |
| 数据库已提交、进入 afterCommit 回调 | 版本 2 和 latest_version=2 均保留，所有引用文件摘要一致 |

父测试等待数据库行锁释放后再核对结果。强杀无法执行 Java 清理回调，前两种情形允许留下无数据库引用的候选文件；在隔离目录中将文件时间设置为超过默认 24 小时宽限，再使用真实回收服务和 Mapper 验证只删除该候选文件。已提交的原文件与新版本均保留，回收后再次校验摘要。生产保留配置未修改。

上述测试是独立 JVM 服务级强杀。另有 `PackagedBackendSmokeIntegrationTest` 和 `PackagedClusterRecoveryIntegrationTest` 验证新构建 JAR 的实际启动、强杀重启、租约恢复及打包资源；二者与写入屏障测试分别计量。

## 修复的问题与边界

1. 删除 `DocTemplate` 与 Lombok `getEnabled()` 类型冲突的多余 `isEnabled()`，解决真实 MyBatis 插入失败。
2. 上传/生成落盘后登记事务完成回调，只在确定回滚时删除新文件；清理失败记录告警并交给回收，不替换原异常，也不在提交状态不明时误删文件。
3. 统一把数据库并发写入冲突映射为 409，避免模型管理并发写入被当作普通 500。

本轮包括后端回归、真实数据库/Redis、三个强杀边界和新 JAR 验收。Office 渲染代码未改动，沿用上一轮已通过的真实 LibreOffice 结果。测试使用随机高位用户 ID，仅清理本轮用户的 Redis 键；早期固定测试编号已改为随机隔离用户并清理其测试额度。

仍需正式环境验证：已有业务数据副本、持续业务负载、数据库服务器崩溃/断电、跨主机网络分区和共享存储故障。单机进程强杀不等同于这些故障。

新增 6 项业务集成、3 个强杀参数场景和 3 项数据库冲突响应回归均通过；新 JAR 的 2 项独立进程验收也通过。其余后端回归与 `mvn -o package` 成功；此次省略未变动的独立 Office 测试组，完整 HTTP 组中的 Office 用例未在本轮重跑。最后一次定向运行会覆盖同名 Surefire XML，统计新增覆盖时需结合 `business-full-regression.log`、`business-final-verification.log` 与 `confirmation-transaction.log`，不能直接把当前目录总数解释成一次全量执行。
