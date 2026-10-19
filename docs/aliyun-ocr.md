# 阿里云统一识别接入

使用官方 Java SDK `com.aliyun:ocr_api20210707:3.1.3` 调用 `RecognizeAllText`（版本 `2021-07-07`）。默认阿里云识别类型为 `Advanced`，请求行文字、表格以及原图四点坐标。文档依据：[RecognizeAllText](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizealltext)。

## 部署配置

1. 阿里云账号开通文字识别统一识别服务，并确认高精版可用及计费额度。
2. 给服务使用的 RAM 身份授予 `ocr:RecognizeAllText`（资源 `*`）权限。无需给前端配置 AccessKey，也不依赖管理端 `scope=OCR` 的模型配置。
3. 在后端进程的环境中设置：

```dotenv
OCR_ENABLED=true
OCR_PROVIDER=aliyun
ALIYUN_OCR_TYPE=Advanced
ALIYUN_OCR_REGION_ID=cn-hangzhou
ALIYUN_OCR_ENDPOINT=ocr-api.cn-hangzhou.aliyuncs.com
ALIYUN_OCR_ACCESS_KEY_ID=<RAM AccessKey ID>
ALIYUN_OCR_ACCESS_KEY_SECRET=<RAM AccessKey Secret>
# 仅使用 STS 临时凭据时设置：
ALIYUN_OCR_SECURITY_TOKEN=
```

Spring Boot 不自动读取工作目录 `.env`；Docker Compose 用 `env_file`，systemd 用 `EnvironmentFile` 或通过部署系统注入。修改后重启后端。密钥只放服务器环境或密钥系统，避免提交到 Git。STS 凭据到期前需更新并重启；当前未实现 STS 自动续期。

代码层面的默认供应商仍为 `cloud-vision`，兼容既有部署；`.env.example` 选择 `aliyun`，但保持 OCR 关闭，填好配置后再启用。切回 `OCR_PROVIDER=cloud-vision` 时才会读取管理端默认 OCR 模型。未知供应商会导致启动配置错误，不会静默回退。

## 现有接口与结果

无需新增接口，继续调用：

- `POST /file/{fileId}/versions/{versionNo}/ocr`，JSON 可用 `{"language":"auto","pages":[1],"dpi":200}`；图片不要传 `pages`。仍按当前用户检查文件版本读取权限。
- `POST /file/{fileId}/versions/{versionNo}/ocr/text` 下载文字，保留既有校正与整理接口。
- PDF 转 DOCX 的 `ocrLanguage` 参数也会使用该供应商识别扫描页。

图片发送二进制内容，文件不必开放公网地址。PDF 使用本地 PDFBox 逐页渲染，仅将选中的扫描页上传，原生文字仍在本地提取。OCR 返回的 `engine` 为 `aliyun-Advanced`。`auto`、`zh`、`en`、`zh+en` 均由阿里云自动识别中英文，语言参数不强制过滤输出。

行坐标由引用文字块坐标合并，四点坐标转为页面内的包围矩形，再交给既有预处理逆变换和裁剪偏移映射。表格零基行列号、包含终点的行列范围转换为 rowSpan/columnSpan。单元格置信度取其引用文字块平均值。缺少坐标或置信度时标记 `needsReview`，不编造定位信息。段落继续使用本项目的行分组规则。

可以显式设 `ALIYUN_OCR_TYPE=General` 使用基础版；不发送高精版专有参数，基础版缺少坐标时无法精准高亮或还原布局。当前适配范围为 Advanced、General，其他卡证/票据/多语言模式需另做字段适配。

## 限额、错误与缓存

单页上传大小取本地配置与 10 MiB 中的较小值；宽高 16～8191 像素，长宽比小于 50，同时遵守本地图片像素限制。超限返回 413，优先降低 DPI、裁剪或拆分页面。连接超时默认 10 秒，读取超时默认 60 秒，可通过 `ALIYUN_OCR_CONNECT_TIMEOUT_MILLIS`、`ALIYUN_OCR_READ_TIMEOUT_MILLIS` 调整。

SDK 禁止自动重试，避免一次调用发生隐式重复计费。限流返回 429，超时 504，凭据/授权错误 503，图片或子图识别错误 422，其他上游错误或异常结构 502。SDK 会先解析 HTTP 响应，`OCR_MAX_RESPONSE_BYTES` 对解析后的 JSON 和缓存生效，并非网络流读取前的硬上限。

沿用 `OCR_CACHE_ENABLED`，缓存保存在存储临时目录的 `OCR_CACHE_DIRECTORY`，由既有临时清理任务回收。键包含图片、语言、供应商、地域、模式和凭据摘要，明文凭据不写入文件名。缓存内容含识别原文，应按业务数据管理临时目录的访问权限。失败结果不缓存；解析规则/置信度阈值变更会在读取原始缓存时重新应用。

## 验收

本地测试使用官方结构的模拟响应，不调用阿里云。上线后用有权限的测试账号上传一张中英文图片及一份扫描 PDF，验证文字、坐标、表格合并和校正保存，再检查控制台调用量。中文真实识别准确率及复杂表格需要实际样本验收；精确分页和完整 Word 排版还原不由 OCR 保证。
