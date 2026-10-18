# 本地部署与演示

本文对应当前单实例后端。命令以 PowerShell 为例；本地 MariaDB 完整迁移、版本事务/并发和配套备份恢复已在随机隔离库验证，见 [数据库生命周期验收](database-lifecycle-testing.md)。完整运行实例的 HTTP 闭环、单实例重启、受控故障和可执行 JAR 强杀后的持久化状态恢复也已通过，见 [完整应用验收](application-flow-testing.md)。正式部署环境、Office 版式和真实模型效果仍需单独验收。默认配置用于本地演示，不能作为已完成生产验收的证明。

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

当前迁移为 V1～V9，包含版本格式、任务幂等、AI 回复确认、模板文件历史、OCR 人工校正、正式回收墓碑/引用索引和执行租约。V6 只为已有模板的当前文件建立 BASELINE 历史，保留其版本号，不能恢复此前未索引的旧文件。升级前备份数据库与正式存储，并在恢复副本上验证迁移、模板替换/恢复及并发冲突；随机隔离库已验证完整升级，已有业务数据恢复副本仍需单独验收。不要修改已经执行过的迁移脚本；后续变更追加新版本。`clean-disabled=true` 不能代替备份。

Redis 用于登录会话及撤销、验证码、上传限流、AI 日额度。应用数据与测试 Redis 分开；不要对运行实例执行 FLUSHDB/FLUSHALL。真实 Redis 测试另见 [安全测试说明](security-testing.md)。

## 2. 环境变量与启动

多实例执行租约默认关闭。需要启用时，所有节点统一设置 `TASK_CLUSTER_ENABLED=true`，按 [执行租约部署与模式切换](execution-leases.md) 处理历史执行记录、共享存储和 JWT 配置；不能混用旧执行者或不同租约模式。

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
| TASK_RECOVER_ON_STARTUP / TASK_REQUEUE_PENDING_ON_STARTUP | 未启用 cluster 的单实例默认进行启动恢复；cluster 开启后自动跳过这两个启动选项，由执行租约维护器恢复过期工作，见 execution-leases.md |
| AI_ENABLED / AI_PROVIDER | 默认关闭 AI；本地演示显式设 true/stub。真实调用设 provider=openai，避免 auto 缺配置时回退桩模型 |
| AI_BASE_URL / AI_API_KEY / AI_MODEL_NAME | 真实兼容协议客户端的地址、密钥和模型；不保证目标服务的工具调用效果，需单独验收 |
| AI_DAILY_CALL_LIMIT / AI_QUOTA_ZONE | 每 ChatModel 调用尝试计数，含失败和智能体重试；0 不限额，默认 Asia/Shanghai 自然日，不扣余额 |
| AI_REQUIRE_CONFIRMATION | 当前 HTTP 回复流程要求 true；先查看 changes，再确认保存 |
| OCR_ENABLED | 默认 false。启用前必须在管理端配置并启用 scope=OCR 的默认 OpenAI 兼容视觉模型；启用后扫描页和图片会上传云端 |
| OCR_DEFAULT_LANGUAGE / OCR_ALLOWED_LANGUAGES | 默认 `auto`；允许 `zh`、`en` 及 `zh+en` 等已开放组合 |
| OCR_PDF_DPI / OCR_MAX_PDF_DPI / OCR_MAX_PAGES | 扫描页渲染 DPI、DPI 上限和单次最多页数；调用方可用 pages 分批 |
| OCR_MAX_WORDS / OCR_MAX_OUTPUT_CHARS / OCR_MAX_IMAGE_BYTES / OCR_MAX_RESPONSE_BYTES | 云端返回行数、单次文字总量、单页上传字节和单次原始响应字节限制 |
| OCR_MAX_TABLE_ROWS / OCR_MAX_TABLE_COLUMNS / OCR_MAX_TABLE_CELLS | 云端可选表格结构的行列和单元格上限；超限拒绝整次响应，不保存部分表格 |
| OCR_LOW_CONFIDENCE_THRESHOLD | 低于该置信度百分比的行标记 needsReview，不会自动篡改文字 |
| OCR_CACHE_ENABLED / OCR_CACHE_DIRECTORY | 缓存云响应，目录位于 STORAGE_LOCAL_TEMP 下；缓存键包含图片摘要、模型配置、语言和密钥摘要，不保存明文密钥 |
| PDF_MAX_CONVERSION_IMAGES / PDF_MAX_CONVERSION_IMAGE_PIXELS_TOTAL | PDF 转 DOCX 最多重建的内嵌图片数和累计像素；超出部分跳过图片，文字仍保留，单张图仍受 STORAGE_MAX_IMAGE_PIXELS 约束 |

当前文本模型读取 res.ai 配置；后台模型配置接口持久化配置和密文，并不会自动替换文本模型客户端。修改文本模型配置后按当前装配方式重新启动并核对返回的 modelName。云 OCR 与此不同：它在请求时读取管理端 `scope=OCR` 的默认、已启用模型配置并解密密钥，因此启用 `OCR_ENABLED=true` 后无需把 OCR 密钥再写入环境变量；修改默认 OCR 配置会影响后续请求。

云 OCR 默认关闭。启用前须检查数据能否发送到该服务商，并配置 HTTPS 接口地址、模型名和 API Key。模型必须支持 OpenAI 兼容 `chat/completions` 的 `image_url` data URL，并能返回约定 JSON；不能因为管理员界面“启用”就视为真实模型质量和合规性已验收。未配置默认模型、密钥解密失败或上游鉴权失败时接口返回 503，不会回退到本地假识别。

验证码日发送量和上传日额度使用进程默认时区；AI 日额度使用 AI_QUOTA_ZONE。部署实例应保持时区一致，必要时使用 JVM 参数 `-Duser.timezone=Asia/Shanghai`；不要把三者误认为同一配置项。

### Office 与字体

PDF 原生文本、PDF/图片预览不需要 Office 后端。DOCX/XLSX 页面预览、Office 导出 PDF 和版式兼容性检查需要：

```powershell
$env:PREVIEW_OFFICE_BACKEND = 'libreoffice'
$env:PREVIEW_OFFICE_EXECUTABLE = 'C:/documenter-runtime/program/soffice.com'
$env:PREVIEW_OFFICE_PYTHON = 'C:/documenter-runtime/program/python.exe'
```

这是部署路径的占位示例，Office 运行时不包含在 Maven JAR 中。Windows 可运行 `./scripts/prepare-office-runtime.ps1`，从官方站点获取固定版本，校验 SHA-256 和签名，再提取独立运行时到 Git 忽略的 `data/tools/libreoffice/runtime`。脚本不注册文件关联，输出可配置的环境变量；本机现有运行时与 UNO Python 已实跑通过。Linux 需安装 LibreOffice 和可 `import uno` 的 Python，并显式设置两个可执行文件路径。转换脚本随 JAR 打包。

每次转换使用独立用户配置及本机命名管道，源文件只读，禁用宏与链接更新；XLSX 外部数据连接和网络公式明确拒绝。DOCX 在导出前更新目录，XLSX 重算支持的公式并刷新图表。转换不会保存回原 DOCX/XLSX，计算结果体现在本次 PDF/预览中。原有 `kit` 后端仍可使用，分别配置 `PREVIEW_OFFICE_KIT_NODE` 和 `PREVIEW_OFFICE_KIT_CLI`，不可把 soffice 路径填为 CLI。

默认 backend=none 时 Office 相关接口返回 503，其他能力可用。PREVIEW_OFFICE_TIMEOUT、PREVIEW_OFFICE_MAX_INPUT_BYTES、PREVIEW_MAX_PAGE_PIXELS 控制资源规模；超时、文档损坏、超限分别映射 504、422、413。

中文排版依赖宿主机字体。PREVIEW_FONT_DIRECTORY、PREVIEW_FONT_FALLBACKS、PREVIEW_INITIAL_FONT_FAMILY 用于已有 kit 字体设置；标准 LibreOffice 使用宿主字体。缺字诊断读取 OOXML 字体名称并与宿主字体对比，不能替代逐页检查。Windows LibreOffice 中文固定样本的实跑记录见 [Office 验收](office-rendering-testing.md)；Microsoft Word、其他平台和业务模板需在对应环境另行验证。

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

管理员不是通过公开注册获得：在受控测试数据库中将已注册账号的 role 改为 ADMIN，重新登录后再访问 /admin/**。不要开放管理员匿名初始化。下载后用 Word 或已配置的 Office 后端核对文件；对应的上传/AI 差异确认/版本下载恢复路径已通过真实 HTTP 自动验收；上方手工 PowerShell 脚本本身未逐行人工执行。

## 5. 备份、恢复与退出演示环境

暂停新请求并停止单实例后，保存同一时点的 MariaDB 数据和 STORAGE_LOCAL_ROOT 正式内容，以及可恢复的部署配置、稳定加密密钥。只备份数据库会丢文档内容，只备份文件会丢归属、版本与会话；MODEL_CONFIG_SECRET 丢失会使模型密文不可读。备份介质、访问控制与保存期限由部署环境制定。

恢复时先在隔离环境还原数据库及同一备份的正式存储，配置相同密钥与正确目录，核对 Flyway 历史。若恢复了 Redis 旧快照，应用保持停服，先预演并显式执行 `scripts/invalidate-restored-sessions.ps1` 清理恢复的登录会话，再启动实例验证新登录、明确版本下载及文档内容。旧快照可能带回已经退出的会话，不能直接接入流量。脚本和本机 AOF/RDB、旧快照、存储目录不可用的实跑结果见 [持久化恢复验收](redis-persistence-testing.md)。

Redis 丢失可能使未过期验证码、限流和额度计数丢失；不得用空 Redis 恢复证明配额连续性，清理会话也不会补回快照之后的额度变化。本地随机隔离库已通过 mariadb-dump 与配套文件归档恢复、逐版本 SHA-256 和 Flyway 校验；正式数据、部署平台的持久化配置、允许的数据丢失窗口及恢复副本仍需单独演练。

STORAGE_LOCAL_TEMP 只用于转换/预览等临时内容，默认 24 小时后回收。不要把正式内容目录当作临时目录清空。正式回收默认关闭且仅预演：软删除默认保留 30 天，活跃文件全部版本和模板全部历史长期保留，无保护引用的 blob 经过 24 小时宽限后才可回收。管理员入口、配置、V8 迁移及验收步骤见 [正式文件回收](storage-recycling.md)；不要按文件日期批量删除。关闭演示终端、清理剪贴板和临时输出，不将密码、Token 或验证码日志提交 Git。
