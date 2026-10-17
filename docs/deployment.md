# 本地部署与演示

本文对应当前单实例后端。命令以 PowerShell 为例；尚未在真实 MariaDB、完整 Office 运行时与真实模型上完成部署联调。默认配置用于本地演示，不能作为已完成生产验收的证明。

## 1. 运行依赖和数据库

准备 JDK 21、Maven、MariaDB、Redis。项目没有前端构建步骤。Maven 首次构建需要获取依赖，缓存齐全后可以加 `-o` 离线运行。

MariaDB 迁移使用 `utf8mb4_uca1400_ai_ci`。先在目标服务确认排序规则存在，再创建专用空数据库：

```sql
SHOW COLLATION LIKE 'utf8mb4_uca1400_ai_ci';
CREATE DATABASE documenter CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;
```

使用该数据库的专用账号，并授予执行迁移所需的建表、建索引、修改结构及业务读写权限。不要在未知结构的已有业务库上试运行。应用默认开启 Flyway baseline-on-migrate；非空库可能被标记为已有基线，不能据此确认旧表结构与当前 V1 一致。已有数据升级前先备份，并在恢复副本上核对表结构及迁移。

启动后检查日志中 Flyway 是否成功，并查询：

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

当前迁移为 V1～V6，包含版本格式、任务幂等、AI 回复确认和模板文件历史。V6 只为已有模板的当前文件建立 BASELINE 历史，保留其版本号，不能恢复此前未索引的旧文件。升级前备份数据库与正式存储，并在恢复副本上验证迁移、模板替换/恢复及并发冲突；本轮尚未执行真实 MariaDB 验收。不要修改已经执行过的迁移脚本；后续变更追加新版本。`clean-disabled=true` 不能代替备份。

Redis 用于登录会话及撤销、验证码、上传限流、AI 日额度。应用数据与测试 Redis 分开；不要对运行实例执行 FLUSHDB/FLUSHALL。真实 Redis 测试另见 [安全测试说明](security-testing.md)。

## 2. 环境变量与启动

`.env.example` 是参数清单，不会被 Spring Boot 自动加载。可在 IDE 的运行配置或部署平台设置变量；下面的写法只影响当前 PowerShell 及其子进程，不会写入版本库：

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_NAME = 'documenter'
$env:DB_USERNAME = 'documenter_app'
$dbSecret = Read-Host '数据库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_USERNAME = 'default'
# 如 Redis 有密码，通过同样的 Read-Host 方式设置 REDIS_PASSWORD。
$env:REDIS_DATABASE = '0'
$env:JWT_SECRET_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:MODEL_CONFIG_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:STORAGE_LOCAL_ROOT = Join-Path (Get-Location) 'data/storage'
$env:STORAGE_LOCAL_TEMP = Join-Path (Get-Location) 'data/tmp'
$env:AI_ENABLED = 'true'
$env:AI_PROVIDER = 'stub'
$env:VERIFICATION_MOCK_ENABLED = 'true'
mvn spring-boot:run
```

随机生成密钥仅适合首次创建本地演示环境。已有实例应从受控密钥存储中读取并保持稳定：更换 JWT 密钥会使原 Token 无效，更换 MODEL_CONFIG_SECRET 会使原模型密文无法解密。不要每次重启重新生成，不要打印密钥或把真实凭据放进命令历史、截图和仓库。

上面的随机字节 API 示例要求 PowerShell 7；Windows PowerShell 5.1 可改用下面的写法生成一个密钥，再分别设置两个变量：

```powershell
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($keyBytes) } finally { $rng.Dispose() }
$env:JWT_SECRET_KEY = [Convert]::ToBase64String($keyBytes)
```

也可构建可执行 JAR：

```powershell
mvn test
if ($LASTEXITCODE -ne 0) { throw '测试失败' }
mvn -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '打包失败' }
java -jar target/documenter-0.0.1-SNAPSHOT.jar
```

默认端口 8080，可通过 SERVER_PORT 修改。目前只有 application-dev.yaml 提供数据源与 Redis 的 profile 配置；自行切换 profile 时必须同时提供这些配置，不能假定存在 production profile。当前 dev 数据库 URL 关闭 TLS，仅适合受控本地环境；远程部署应另行配置数据源 URL、TLS、访问控制和外部 HTTPS。

application-service.yaml 是本地维护的部署统一配置，放在 src/main/resources 下，按项目约定需要随 JAR 打包，但不提交 Git；.gitignore 已忽略该文件。构建前应在本机准备好它，交付 JAR 时包含这份配置，源码仓库不保存其内容。Git 忽略与 Maven 打包是两套独立规则，不应把它加入打包排除项。service profile 需要在部署启动配置中显式激活，例如 SPRING_PROFILES_ACTIVE=dev,service；实际使用哪些 profile 应与部署配置一致。

## 3. 参数和能力边界

所有示例值和默认值见 [.env.example](../.env.example)。

| 参数 | 行为 |
| --- | --- |
| DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD | 数据源及 Flyway 使用的连接 |
| REDIS_HOST / REDIS_PORT / REDIS_USERNAME / REDIS_PASSWORD / REDIS_DATABASE | Redis 连接；不可用会影响认证与限流 |
| JWT_SECRET_KEY / MODEL_CONFIG_SECRET | 签名与数据库模型密钥加密；分别使用稳定随机密钥 |
| STORAGE_LOCAL_ROOT / STORAGE_LOCAL_TEMP | 正式内容与可回收临时内容，建议分离目录 |
| STORAGE_MAX_UPLOAD_BYTES / STORAGE_MAX_REQUEST_BYTES | 默认 50 MiB 单文件、55 MiB 总请求；增加单文件上限时同步增加总请求上限，留足 multipart 开销 |
| UPLOAD_DAILY_LIMIT | 每用户自然日上传尝试上限，默认 60；历史属性名 hourly-limit 仍沿用，实际不是小时额度 |
| VERIFICATION_MOCK_ENABLED | 默认 true，会把明文验证码写入日志，仅供本地演示。false 移除 Mock Bean；当前尚无真实发送器，直接关闭会因缺少 VerificationCodeSender 而启动失败，需先接入真实渠道 |
| VERIFICATION_CODE_LENGTH / VERIFICATION_TTL / VERIFICATION_RESEND_INTERVAL / VERIFICATION_DAILY_LIMIT / VERIFICATION_MAX_ATTEMPTS | 验证码长度、有效期、重发间隔、日发送尝试上限和错误次数；HTTP 校验允许 4～8 位数字，应保持长度匹配 |
| TASK_CORE_POOL_SIZE / TASK_MAX_POOL_SIZE / TASK_QUEUE_CAPACITY | 两个独立有界线程池各使用同样容量；队列满返回任务 QUEUE_FULL 失败，通过原有重试接口处理 |
| TASK_DEFAULT_TIMEOUT / TASK_DEFAULT_MAX_RETRY / TASK_KEEP_ALIVE / TASK_SHUTDOWN_AWAIT | 默认超时、处理重试、线程空闲和停机等待；外部强制终止时间要留足余量 |
| TASK_PLACEHOLDER_ENABLED | 默认 false。CONVERT/EXPORT 已有真实处理器；该开关仅用于历史 OCR/AI_EDIT 兼容排查，不应在正常部署中开启 |
| TASK_RECOVER_ON_STARTUP / TASK_REQUEUE_PENDING_ON_STARTUP | 默认把中断的运行任务/回复标为失败，并把尚未开始的 CONVERT/EXPORT 重新入队；当前恢复逻辑面向单实例，多实例部署必须谨慎启用 |
| AI_ENABLED / AI_PROVIDER | 默认关闭 AI；本地演示显式设 true/stub。真实调用设 provider=openai，避免 auto 缺配置时回退桩模型 |
| AI_BASE_URL / AI_API_KEY / AI_MODEL_NAME | 真实兼容协议客户端的地址、密钥和模型；不保证目标服务的工具调用效果，需单独验收 |
| AI_DAILY_CALL_LIMIT / AI_QUOTA_ZONE | 每 ChatModel 调用尝试计数，含失败和智能体重试；0 不限额，默认 Asia/Shanghai 自然日，不扣余额 |
| AI_REQUIRE_CONFIRMATION | 当前 HTTP 回复流程要求 true；先查看 changes，再确认保存 |
| PDF_MAX_CONVERSION_IMAGES / PDF_MAX_CONVERSION_IMAGE_PIXELS_TOTAL | PDF 转 DOCX 最多重建的内嵌图片数和累计像素；超出部分跳过图片，文字仍保留，单张图仍受 STORAGE_MAX_IMAGE_PIXELS 约束 |

当前活动模型读取 res.ai 配置；后台模型配置接口持久化配置和密文，并不会自动替换正在使用的客户端。修改模型配置后按当前装配方式重新启动并核对返回的 modelName。

验证码日发送量和上传日额度使用进程默认时区；AI 日额度使用 AI_QUOTA_ZONE。部署实例应保持时区一致，必要时使用 JVM 参数 `-Duser.timezone=Asia/Shanghai`；不要把三者误认为同一配置项。

### Office 与字体

PDF 原生文本、PDF/图片预览不需要 Office 后端。DOCX/XLSX 页面预览、Office 导出 PDF 和版式兼容性检查需要：

```powershell
$env:PREVIEW_OFFICE_BACKEND = 'kit'
$env:PREVIEW_OFFICE_KIT_NODE = 'C:/documenter-runtime/node.exe'
$env:PREVIEW_OFFICE_KIT_CLI = 'C:/documenter-runtime/libreoffice-kit/lib/cli.js'
```

这是部署路径的占位示例，运行时不包含在 Maven JAR 中，需自行准备与当前 CLI 协议匹配的 Node、kit 及其 native 依赖。普通 LibreOffice 的 soffice 路径不能直接填为 cli.js。代码保留了开发机路径作为回退，但部署必须显式配置；设置 kit 并不代表渲染成功，应执行现有 compatibility-check 接口并检查结果。

默认 backend=none 时 Office 相关接口返回 503，其他能力可用。PREVIEW_OFFICE_TIMEOUT、PREVIEW_OFFICE_MAX_INPUT_BYTES、PREVIEW_MAX_PAGE_PIXELS 控制资源规模；超时、文档损坏、超限分别映射 504、422、413。

中文排版依赖宿主机字体。PREVIEW_FONT_DIRECTORY、PREVIEW_FONT_FALLBACKS、PREVIEW_INITIAL_FONT_FAMILY 用于字体设置与缺字诊断；Windows native 后端实际仍使用系统字体，不能靠填写目录保证显示正确。逐页检查中文、分页和表格，缺字诊断和真实 Office 跨平台兼容性仍需验收。

## 4. 使用现有 API 演示

以下步骤会在自己的本地测试账号下创建文件、会话和版本。先按第 2 节以 AI_ENABLED=true、AI_PROVIDER=stub 启动；这是确定性工具演示，不代表真实模型理解自然语言。

在另一个 PowerShell 中执行以下内容。使用未占用用户名，不要填真实生产账号：

```powershell
$base = 'http://localhost:8080'
$headers = @{}
function Invoke-DemoApi([string]$method, [string]$path, $body = $null) {
    $request = @{ Method = $method; Uri = "$base$path"; Headers = $headers }
    if ($null -ne $body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 12))
    }
    $response = Invoke-RestMethod @request
    if ($response.code -ne 0) { throw "业务失败 $($response.code): $($response.msg)" }
    return $response.data
}
$username = Read-Host '新建演示用户名（4～20 位字母数字下划线）'
$passwordSecret = Read-Host '演示密码（8～64 位）' -AsSecureString
$password = [System.Net.NetworkCredential]::new('', $passwordSecret).Password
$null = Invoke-DemoApi POST '/user/register' @{ username = $username; password = $password }
$tokens = Invoke-DemoApi POST '/user/login' @{ account = $username; password = $password }
$password = $null
$headers.Authorization = "Bearer $($tokens.accessToken)"

$file = Invoke-DemoApi POST '/file/docx/generate' @{
    fileName = '部署演示.docx'; title = '部署演示'
    blocks = @(@{ type = 'PARAGRAPH'; text = '待修改的原文' })
}
$fileId = $file.id
$version = $file.latestVersion
$structure = Invoke-DemoApi GET "/file/$fileId/versions/$version/structure"
$paragraph = $structure.blocks | Where-Object type -eq 'PARAGRAPH' | Select-Object -First 1
if ($null -eq $paragraph) { throw '没有可编辑段落' }
$session = Invoke-DemoApi POST '/ai/sessions' @{ title = '部署演示'; fileId = $fileId }
$sessionId = $session.id
$arguments = @{
    operation = 'REPLACE_TEXT'; target = $paragraph.blockId
    content = '已确认保存的演示内容'; reason = '验证待确认流程'
} | ConvertTo-Json -Compress
$reply = Invoke-DemoApi POST "/ai/sessions/$sessionId/replies" @{
    content = "[stub-tool] edit_paragraph $arguments"; versionNo = $version
    idempotencyKey = [guid]::NewGuid().ToString()
}
if ($reply.runStatus -ne 'COMPLETED' -or $reply.planStatus -ne 'PENDING') {
    throw '回复未形成待确认计划，请检查 AI 配置与返回结果'
}
$reply.changes | Format-Table type, target, before, after, reason
```

确认上方差异符合预期后，在同一终端单独执行确认与下载：

```powershell
$messageId = $reply.message.id
$confirmed = Invoke-DemoApi POST "/ai/sessions/$sessionId/replies/$messageId/confirm"
$outputVersion = $confirmed.outputVersion
if ($confirmed.planStatus -ne 'CONFIRMED' -or $null -eq $outputVersion) { throw '确认未成功' }
$downloadDirectory = Join-Path (Get-Location) 'data/demo-output'
$null = New-Item -ItemType Directory -Path $downloadDirectory -Force
$downloadPath = Join-Path $downloadDirectory '部署演示-已确认.docx'
Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "$base/file/$fileId/versions/$outputVersion/download" -OutFile $downloadPath
Write-Host "已下载明确版本 $outputVersion 到 $downloadPath"
```

下载的文件是确认后的明确版本。再次确认同一回复应返回相同 outputVersion，不生成重复版本；需要放弃时使用现有 discard 入口。确认前若其他操作已更新文件，应得到业务码 409，重新读取版本并发送新要求。所有 JSON 接口都要检查响应体 code，不能只看 HTTP 200。没有新增健康检查或演示专用 HTTP 接口。

管理员不是通过公开注册获得：在受控测试数据库中将已注册账号的 role 改为 ADMIN，重新登录后再访问 /admin/**。不要开放管理员匿名初始化。下载后用 Word 或已配置的 Office 后端核对文件；当前文档步骤尚未在完整运行实例上逐条执行。

## 5. 备份、恢复与退出演示环境

暂停新请求并停止单实例后，保存同一时点的 MariaDB 数据和 STORAGE_LOCAL_ROOT 正式内容，以及可恢复的部署配置、稳定加密密钥。只备份数据库会丢文档内容，只备份文件会丢归属、版本与会话；MODEL_CONFIG_SECRET 丢失会使模型密文不可读。备份介质、访问控制与保存期限由部署环境制定。

恢复时先在隔离环境还原数据库及同一备份的正式存储，配置相同密钥与正确目录，核对 Flyway 历史，再启动一个实例验证登录、明确版本下载及文档内容。Redis 丢失将使现有会话不可用，也可能使未过期验证码、限流和额度计数丢失；不得用空 Redis 恢复证明配额连续性。备份恢复演练仍待真实环境验收。

STORAGE_LOCAL_TEMP 只用于转换/预览等临时内容，默认 24 小时后回收。不要把正式内容目录当作临时目录清空。软删除文件和旧模板内容仍可能被保留，当前没有正式存储的全量孤儿文件回收；不要按文件日期批量删除。关闭演示终端、清理剪贴板和临时输出，不将密码、Token 或验证码日志提交 Git。
