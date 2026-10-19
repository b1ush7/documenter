# 完整后端 HTTP、重启与故障验收

`ApplicationFlowIntegrationTest` 启动真实 Spring Boot/Tomcat 后端，通过随机本机端口请求 API，连接真实 MariaDB 与 Redis；不使用 MockMvc 替代 HTTP。短信始终 Mock，OCR 始终关闭，正常 AI 流程使用明确标注的本地 stub。

## 基础流程验收

| 用例 | 实际验证 |
| --- | --- |
| HTTP 闭环与重启 | 注册、密码登录、DOCX 上传、读取明确版本结构、AI 差异、确认与重复确认、下载并用 POI 核对内容、恢复原版本、PDF PNG 预览、SSE 完整结束及审计身份/请求标识、Refresh Token 单次轮换、跨用户 404、普通用户管理端 403、退出撤销 |
| HTTP 失败边界 | 缺 Office 后端时元信息 previewable=false，实际渲染 503；导出任务失败/重试不新增文档版本；超过上传限制返回 413，畸形 multipart 或缺必填文件返回 400 |
| 任务运行与模型故障 | 真实任务表/步骤表上的超时中断、取消、处理池及调度池饱和；本机模拟模型 HTTP 503/超时后回复 FAILED、无待确认计划且文档不变 |

第三行的任务引擎用受控阻塞处理器制造确定性超时，不声称测试了远程模型或真实 Office 服务的故障。模型故障仅向测试创建的 `127.0.0.1` HTTP 服务发送请求，不访问远程供应商。

另有两个显式启用的大文件/并发传输与双应用实例用例，均已在本机执行通过；具体样本规模、开关及多实例限制见 [负载和双实例验收](load-and-multi-instance-testing.md)。

重启用例关闭完整应用上下文，构造持久化 RUNNING/PENDING 任务和 RUNNING AI 回复，再使用同一数据库、存储与 JWT 密钥启动。验证 RUNNING 任务变为 FAILED/RESTARTED、AI 回复变为 FAILED，PENDING PDF 转 DOCX 重新入队并真正产出可编辑 DOCX；已有登录态与历史内容仍可读取。这项用例验证单实例上下文重启；独立进程强杀验证见下文，多实例租约验证见 [执行租约](execution-leases.md)。

本轮验收发现并修复：OCR 组件的 Jackson 2 注入类型与 Boot 4 Jackson 3 不一致导致应用无法启动；容器层上传超限误报 500；SSE 的 ASYNC 完成分派丢失 JWT 身份；SSE 工作线程缺少审计用户、来源与请求上下文。普通请求权限和远程 OCR 默认关闭规则保持原有语义。

## 数据隔离与执行

测试只新建随机 `documenter_app_it_<UUID>` 库，退出时仅删除本轮成功创建的库。正式文件目录由 JUnit 临时目录隔离。Redis 使用随机大整数用户 ID 和 UUID 会话，结束时只删除这些用户的会话索引/会话键、上传限额键及 AI 日额度键，不执行 FLUSHDB/FLUSHALL。

```powershell
$env:DOCUMENTER_APP_TEST_ENABLED = 'true'
$env:DOCUMENTER_LIFECYCLE_DB_TEST_URL = 'jdbc:mariadb://127.0.0.1:3306/'
$env:DOCUMENTER_LIFECYCLE_DB_TEST_USER = 'documenter_test'
$secret = Read-Host '专用测试账号密码' -AsSecureString
$env:DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$env:DOCUMENTER_TEST_REDIS_PORT = '6379'
mvn -o test '-Dtest=ApplicationFlowIntegrationTest'
```

需要建库/删库权限；仅接受本机服务器级 JDBC URL。测试 Redis 为默认用户、无密码；建议独立本机测试实例。未显式启用时跳过，启用后失败会如实报错。

2026-10-17 已在本机 MariaDB 13.0.2 / Redis 上执行通过。DOCX/XLSX 的实际 Office 版式、真实模型质量、业务模板、正式数据恢复副本和跨主机部署仍是独立验收项；本测试中的结构读取不等于 Office 版式验收。

## 可执行 JAR 验收

V9 执行租约及两个实际 JAR 进程间的失联恢复另见 [执行租约验收](execution-leases.md)。本节为默认关闭 cluster 的单实例兼容路径。

本机已完成离线 `package`，并通过 `PackagedBackendSmokeIntegrationTest` 在独立 Java 子进程中实际启动生成的 JAR。该测试先检查包内的 `application-service.yaml` 和 V8/V9 迁移资源，再通过真实 HTTP 验证匿名访问 `/user/me` 返回 401、注册接口成功且数据落入随机测试库，校验 Flyway 九个迁移均成功；结束后停止子进程并删除该随机库。测试不登录、不创建 Redis 会话。

测试进一步在子进程存活期间写入 RUNNING 任务/步骤、已完成步骤、PENDING 历史图片生成任务及 RUNNING AI 回复夹具，然后通过 `Process.destroyForcibly()` 强杀本测试启动的进程，使用同一数据库和存储重新启动 JAR。验证任务 FAILED/RESTARTED，运行中步骤 FAILED 并记录结束时间/耗时，已完成步骤不变，AI 回复 FAILED；旧排队图片生成任务被重新调度并以 TASK_TYPE_DISABLED 收尾。再次强杀并重启，校验恢复幂等、版本数不增加、4 MiB 存储字节夹具 SHA-256 不变，以及 HTTP 仍正常鉴权。存储夹具仅用于验证持久性，不作为真实图片格式或图片处理验收。

此次补测修复了重启后任务已失败、其步骤仍永久 RUNNING 的遗漏；步骤和任务在同一数据库事务内收尾，数据库错误会回滚且不会开始重新入队。该验证针对已提交的持久化状态；未模拟文件写入一半、数据库提交瞬间断电、真实外部请求执行中被杀或多实例并发接管。

在前述本地数据库环境变量设置后执行：

```powershell
mvn -o package
$env:DOCUMENTER_PACKAGED_TEST_JAR = Join-Path $PWD 'target/documenter-0.0.1-SNAPSHOT.jar'
mvn -o test '-Dtest=PackagedBackendSmokeIntegrationTest'
```

未提供 JAR 路径时此用例跳过。不要将旧包的启动结果用于证明新源码可部署；先打包，再验证指定产物。JAR 启动验收仍不等于正式反向代理、容器编排、负载或多实例验收。
