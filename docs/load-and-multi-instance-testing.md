# 本机大文件、边界与双实例验收

2026-10-17 在本机 MariaDB 13.0.2、Redis 6379 上通过 `ApplicationFlowIntegrationTest` 的两个显式启用用例。HTTP 服务监听随机回环端口，数据仅写入随机 `documenter_app_it_<UUID>` 库和 JUnit 临时文件目录；清理规则沿用 [完整应用验收](application-flow-testing.md)，不修改业务库、正式存储或其他用户的 Redis 键。验证码使用 Mock，OCR 关闭，不访问远程模型。

## 文件边界及并发传输

采用默认业务上限：单文件 50 MiB、图片 40,000,000 像素、PDF 200 页。客户端使用文件流发送 multipart 和接收下载，不把大文件反复复制到内存数组。

| 场景 | 验证结果 |
| --- | --- |
| 4000×4000 随机像素 PNG，48,036,288 字节 | 真实 HTTP 上传成功，服务端 SHA-256 与源文件一致 |
| 图片 8000×5000 / 8000×5001 | 40,000,000 像素接受，40,008,000 像素拒绝并返回业务码 413 |
| 200 / 201 页有效 PDF | 200 页接受，完整提取并核对最后一页文字；201 页拒绝，业务码 413 |
| 50 MiB + 1 字节上传 | 容器实际 HTTP 413，响应业务码 413 |
| 8 路同时上传，各 3,003,075 字节 PNG | 全部成功，8 个不同文件 ID，摘要一致，每个文件只有初始版本 |
| 4 路并发下载前述约 48 MB 文件 | 全部 HTTP 200，大小和逐文件 SHA-256 一致 |
| 数据和磁盘一致性 | 11 个成功文件恰好对应 11 个版本及 11 个正式存储文件；拒绝请求不产生记录或孤儿文件 |

首轮测量：大 PNG 上传 3461 ms，200 页 PDF 内容读取 114 ms；8 路上传各 865–1073 ms，4 路大文件下载（含本地摘要核对）各 379–391 ms。每次运行生成 `target/backend-load-report.json`，记录当前样本大小与各请求耗时；这些是短时本机合成负载的观察值，没有设置生产延迟承诺，也不代表生产带宽、持续吞吐、容量、内存峰值或真实业务复杂文档表现。后续运行的数字以该次报告为准。

## 双应用实例的已验证范围

同一 JVM 启动两个完整 Spring Boot/Tomcat 上下文，各自持有 Mapper、Hikari 连接池和任务线程池，共享测试数据库、Redis、存储和 JWT 密钥。两个实例均显式设置：

```text
TASK_RECOVER_ON_STARTUP=false
TASK_REQUEUE_PENDING_ON_STARTUP=false
```

验证同一个登录 Token 能在两个端口使用；第二实例启动后，预先持久化的 RUNNING 任务、步骤与 AI 回复保持运行状态；四个 PDF 转 DOCX 任务各由两个实例的真实执行引擎同时尝试占用，共八路执行竞争。每个任务均成功、只保存一个 v2 输出和一套成功步骤，输出可由 POI 重开并核对文字，未抢到任务的执行者退出。

本节验证未开启 cluster 的兼容路径，没有自动故障恢复。现已另行实现可选执行租约、心跳、过期恢复及旧执行者写入隔离，见 [执行租约验收与部署规则](execution-leases.md)。本节的两个应用上下文不等同于跨主机集群；默认配置仍是单实例，租约模式必须统一显式开启。

## 复现

先按 [完整应用验收](application-flow-testing.md) 设置本机数据库、专用测试账号密码及 Redis 端口，再执行：

```powershell
$env:DOCUMENTER_APP_TEST_ENABLED = 'true'
$env:DOCUMENTER_LOAD_TEST_ENABLED = 'true'
$env:DOCUMENTER_MULTI_INSTANCE_TEST_ENABLED = 'true'
mvn -o test '-Dtest=ApplicationFlowIntegrationTest'
```

需数据库建库/删库权限、足够临时磁盘空间（约 400 MB）及 Java 堆空间。本机执行使用 1536 MiB 最大堆；这不是峰值内存测量。未设置相应用例开关时跳过，显式启用后任一请求、摘要或一致性失败均令测试失败。

2026-10-18 另补 4 路持续 30 秒的真实 HTTP 上传/下载/版本历史基线，以及数据库写入连接断开、独立临时 Redis 强杀后的恢复，见 [依赖恢复验收](dependency-recovery-testing.md)。该项是本机有节拍基线，不代表生产容量。
