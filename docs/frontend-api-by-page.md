# Documenter 前端联调文档（按页面组织）

> 生成依据：`src/main/java/com/documenter/controller` 的实际端点与 DTO/VO 字段。
> 与 `API.md` 的关系：`API.md` 由后端维护、面向接口；本文档面向前端，按**页面**重组。
> **注意 `API.md` 当前已过时**（部分路径缺少 `/file/{fileId}` 前缀、缺少新增端点的说明），
> 联调请以本文档为准。

## 0. 通用约定

### 0.1 基地址与鉴权

```
Base URL:  http://<host>:8080
请求头:     Authorization: Bearer <accessToken>
            X-Request-ID: <可选的调用方追踪 ID，长度 8-64，仅允许 A-Za-z0-9_.:->
```

响应头会回写 `X-Request-ID`；服务端日志按该 ID 串联，**报错时请把它带上，便于后端定位**。

### 0.2 统一响应体

```json
{ "code": 0, "msg": "", "data": {} }
```

- `code = 0` 表示成功；成功时 `msg` 可能是空串，也可能是如 `"登录成功"` 的提示，**前端不要依赖 msg 判定成功**
- `code != 0` 表示业务失败，此时 HTTP 状态码**多数为 200**，只有认证层例外（见 0.4）

### 0.3 分页响应

```json
{ "records": [], "total": 0, "page": 1, "size": 20, "pages": 0 }
```

分页参数统一为 `page`（从 1 开始）与 `size`（最大 100）。

### 0.4 HTTP 状态码与错误码对照

| HTTP | 场景 | 前端处理 |
| --- | --- | --- |
| 200 | 业务成功，或业务失败（看 `code`） | 按 `code` 判断 |
| 401 | 认证层拒绝：Token 缺失/失效/过期 | **触发 Token 刷新流程**（见 0.5） |
| 403 | 已认证但权限不足 | 提示无权限；若为管理员接口则跳回首页 |
| 404 | 资源不存在**或不属于当前用户** | 统一提示"不存在"，不要提示"无权限" |

业务错误码（响应体 `code`）常用值：

| code | 含义 |
| --- | --- |
| 400 | 参数校验失败 / 业务前置条件不满足 |
| 401 | 账号或密码错误；Refresh Token 失效 |
| 403 | 账号被禁用 |
| 409 | 状态冲突（并发编辑、重复操作、资源被占用） |
| 413 | 文件超过大小限制 |
| 415 | 文件内容类型不受支持 |
| 429 | 频率或额度限制（验证码、上传次数、重试次数、每日 AI 调用次数） |
| 500 | 服务端异常 |
| 502 | 外部服务失败（短信发送） |

**404 的一个关键约定**：为避免攻击者枚举 ID，后端对"他人的资源"一律返回 404 而不是 403。
前端对 404 统一提示"资源不存在或已删除"，**不要提示"你没有权限"**，否则会泄露"该 ID 真实存在"。

### 0.5 Token 刷新流程（必须实现）

```
任意请求收到 HTTP 401
  ├─ 有 refreshToken → POST /user/refresh { refreshToken }
  │    ├─ 成功 → 覆盖本地双 Token，重放原请求（仅重放一次）
  │    └─ 失败（401 或 code=401）→ 清空 Token，跳登录页
  └─ 无 refreshToken → 跳登录页
```

实现要点（踩坑清单）：

1. **刷新必须加互斥**：多个请求同时 401 时只允许一个真正去刷新，其余等待结果。
   并发刷新会让彼此的 Refresh Token 互相作废（轮换是单次有效的）。
2. **只重放一次**：重放后仍 401 就直接跳登录页，防止无限循环。
3. **别只靠定时刷新**：设备休眠、时钟漂移都会让它失准，以 401 驱动为主路径。
4. **跨标签页同步**：多个标签页各持一份 Token 会互相覆盖，建议 `localStorage` + `storage` 事件同步。

Token 有效期：Access 15 分钟，Refresh 7 天。刷新返回新的双 Token（旧 Refresh 立即失效）。

### 0.6 枚举取值速查

| 枚举 | 取值 |
| --- | --- |
| 用户角色 | `USER` / `ADMIN` |
| 账号状态 | `ENABLED` / `DISABLED` |
| 文件状态 | `READY` / `DELETED` / `QUARANTINED` |
| 文件来源 | `UPLOAD` / `GENERATED` / `CONVERTED` |
| 版本变更类型 | `UPLOAD` / `AI_EDIT` / `MANUAL_EDIT` / `CONVERT` / `RESTORE` |
| 任务状态 | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED` |
| 任务类型 | 新建：`CONVERT` / `EXPORT`；`OCR` / `AI_EDIT` / `IMAGE_GEN` 仅历史展示/查询 |
| 步骤状态 | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `SKIPPED` |
| 会话状态 | `ACTIVE` / `ARCHIVED` |
| 消息角色 | `USER` / `ASSISTANT` / `TOOL` / `SYSTEM` |
| 模型用途 | `TEXT` / `IMAGE` / `OCR` |
| 配置状态 | `ENABLED` / `DISABLED` |

---

# 一、认证模块

## P1 登录页

支持密码登录与验证码登录两种方式（Tab 切换）。

### 密码登录

```
POST /user/login
```

请求：

```json
{ "account": "用户名或手机号", "password": "密码" }
```

| 字段 | 类型 | 必填 | 校验 |
| --- | --- | --- | --- |
| account | string | 是 | 非空；填手机号时按 `^1[3-9]\d{9}$` 识别为手机号查询 |
| password | string | 是 | 非空 |

响应 `data`：`TokenPair`

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": 900,
  "refreshExpiresIn": 604800
}
```

`expiresIn` / `refreshExpiresIn` 单位均为**秒**。

错误：

| code | 提示 |
| --- | --- |
| 400 | 账号不能为空 |
| 401 | 账号或密码错误（**账号不存在与密码错误返回同一提示，前端不要区分**） |
| 403 | 账号已被禁用，请联系管理员 |

### 验证码登录

```
POST /user/login/code     发送验证码   { "phone": "13800000000" }
POST /user/login/by-code  验证码登录   { "phone": "...", "code": "123456" }
```

- 发送接口要求手机号**已注册且账号未禁用**
- 发送响应 `data` 是**脱敏手机号**（如 `****0000`），**不会返回验证码**
- 开发环境验证码打印在**服务端日志**里（`[Mock 验证码]` 开头），联调时找后端要

### 前置：发送验证码

```
POST /user/login/code
```

请求 `{ "phone": "13800000000" }`（校验 `^1[3-9]\d{9}$`）

### 独立消费注册验证码

```
POST /user/verify/code
```

请求 `{ "phone": "13800000000", "code": "123456" }`

> **注意：本接口会真正消费验证码**，成功后验证码立即失效。
> 注册表单应直接提交 `/user/register/by-code`，不能先调用本接口再用同一验证码注册。
> 另外它固定使用 **REGISTER** 场景，不能用于校验登录或找回密码的验证码。

独立校验成功时 data 为 null；发送验证码接口的 data 才是脱敏手机号。

错误：

| code | 含义 |
| --- | --- |
| 400 | 手机号格式不正确 / 该手机号尚未注册 |
| 403 | 账号已被禁用 |
| 429 | 验证码发送过于频繁（默认 60 秒间隔）/ 今日发送次数已达上限（默认 10 次） |
| 502 | 验证码发送失败（外部服务问题） |
| 503 | 已投递但无法确认验证码保存成功，需在重发间隔后重新获取 |

消费时错误次数未达上限返回 400；达到上限的那次返回 429 并立即作废。已消费、已作废或已过期的验证码再次提交返回 400。不能在前端把独立校验结果当成可重复使用的注册凭证。

## P2 注册页

### 密码注册（手机号选填）

```
POST /user/register
```

```json
{ "username": "user_01", "phone": "13800000000", "password": "12345678" }
```

| 字段 | 校验 |
| --- | --- |
| username | 4-20 位，仅字母数字下划线 |
| phone | 选填；填了则必须匹配 `^1[3-9]\d{9}$` |
| password | 8-64 位 |

### 验证码注册（手机号必填）

```
POST /user/register/code    发送验证码（要求手机号未注册）
POST /user/register/by-code 注册
```

验证码注册请求：

```json
{ "username": "user_01", "phone": "13800000000", "code": "123456", "password": "12345678" }
```

响应 `data`：`UserInfo`

```json
{
  "id": 1, "username": "user_01", "phone": "13800000000",
  "role": "USER", "status": "ENABLED", "balance": 5.000000,
  "createTime": "2026-10-12T10:00:00", "lastLoginTime": null
}
```

错误：`400` 用户名已被占用 / 手机号已被注册 / 验证码不正确；`429` 验证码错误次数过多。

## P3 找回密码页

```
POST /user/reset-password/code   发送验证码（要求手机号已注册且账号未禁用）
POST /user/reset-password        重设密码
```

重设请求：

```json
{ "phone": "13800000000", "code": "123456", "newPassword": "newpass123" }
```

响应：`data` 为 `null`，`msg` 为 `"密码已重置，请使用新密码登录"`。

**重要**：成功后服务端会**撤销该用户的全部会话**，前端必须清空本地 Token 并跳登录页。
这一步是刻意的——改密的目的通常是"怀疑账号被他人使用"，保留任何旧会话都会让改密失去意义。

## P4 修改密码页（需登录）

```
POST /user/change-password
```

```json
{ "oldPassword": "12345678", "newPassword": "newpass123" }
```

响应：`data` 为 `null`，`msg` 为 `"密码已修改，请重新登录"`。

**重要**：成功后**当前会话也会被撤销**，所有设备都要重新登录。前端收到 200 后立即清 Token 跳登录页。
错误：`400` 原密码不正确；`401` 未登录。

## P5 个人中心

```
GET  /user/me              当前用户信息（结构同 UserInfo）
POST /user/logout-all      退出全部其他设备
POST /user/logout          退出当前设备（Spring Security 处理）
```

`logout-all` 响应 `data` 为**实际撤销的会话数量**（数字），当前设备保留登录。

```json
{ "code": 0, "msg": "已退出全部其他设备", "data": 2 }
```

---

# 二、文件与工作区

## P6 文件列表页

```
GET /file/list?page=1&size=20&keyword=&status=
```

| 参数 | 说明 |
| --- | --- |
| page | 从 1 开始 |
| size | 最大 100 |
| keyword | 按展示名模糊搜索，可选 |
| status | `READY`/`DELETED`/`QUARANTINED`，**留空默认只返回 READY** |

响应 `data`：`PageResult<FileAsset>`

```json
{
  "records": [{
    "id": 10, "originalName": "报告.docx", "displayName": "季度报告.docx",
    "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "extension": "docx", "sizeBytes": 20480, "sha256": "…",
    "source": "UPLOAD", "status": "READY", "latestVersion": 3,
    "createTime": "2026-10-12T10:00:00", "updateTime": "2026-10-12T11:00:00"
  }],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

**注意：响应不含 `storageKey`**（服务端内部信息，任何接口都不会返回）。

本页需要的操作：

| 操作 | 接口 |
| --- | --- |
| 重命名 | `PUT /file/{fileId}/name` `{ "displayName": "新名字.docx" }` |
| 删除 | `DELETE /file/{fileId}`（软删除，可再次调用，幂等） |
| 下载 | `GET /file/{fileId}/download`（返回文件流，`Content-Disposition: attachment`） |

## P7 上传

```
POST /file/upload        Content-Type: multipart/form-data
```

表单字段：`file`（单个文件）

响应 `data`：`FileAsset`（同列表项结构，`latestVersion` 为 1）

**上传成功后会自动建立版本 1**，无需前端再调建版本接口。

错误处理（前端必须区分）：

| code | 场景 | 建议提示 |
| --- | --- | --- |
| 400 | 文件为空 | 请选择文件 |
| 413 | 超过 50MB | 文件过大 |
| 415 | 内容类型不受支持 | **直接用 `msg`**，后端给出了具体原因，例如"检测到旧版 Office 二进制格式（.doc/.xls/.ppt），当前仅支持 .docx/.xlsx，请先另存为新格式" |
| 429 | 今日上传次数已达上限 | 请稍后再试 |

**类型识别只看文件内容**，不看扩展名和 `Content-Type`。把 `.exe` 改名成 `.docx` 会被 415 拒绝，这是预期行为。

支持的格式：`DOCX`、`XLSX`、`PDF`、`PNG`、`JPEG`（另有 GIF/BMP 可识别）。

## P8 文件详情 / 预览工作区

这是最重的页面，建议按格式拆成独立视图组件，**不要做成一个大页面**。

### 8.1 顶部：版本切换器

```
GET /file/{fileId}/versions                       版本列表
GET /file/{fileId}/versions/{versionNo}           版本详情
GET /file/{fileId}/versions/{versionNo}/download  下载指定版本
POST /file/{fileId}/versions/{versionNo}/restore  恢复到该版本
GET /file/{fileId}/download                       下载当前版本
```

版本列表响应 `data`：`Version[]`

```json
[{
  "id": 31, "fileId": 10, "versionNo": 3, "parentVersion": 2,
  "contentType": "…wordprocessingml.document", "extension": "docx",
  "sizeBytes": 21504, "sha256": "…",
  "instruction": "把第二段改得更正式", "resultSummary": "替换了 1 个段落",
  "changeType": "AI_EDIT", "createTime": "2026-10-12T11:00:00"
}]
```

**版本一致性要求**：预览与下载必须来自**同一个 versionNo**，否则会出现"看到的是新版、下到的是旧版"。
版本下载的文件名会自动带版本号（`报告-v2.docx`），便于区分。

恢复版本请求（`expectVersion` 可选，用于并发保护）：

```
POST /file/{fileId}/versions/{versionNo}/restore?expectVersion=3
```

恢复**不覆盖历史**，而是新建一个 `changeType=RESTORE` 的版本，因此恢复本身也可被再次撤销。
并发冲突时返回 `409`，`msg` 会告知当前实际版本号。

### 8.1.1 通用页面版式预览

```
GET /file/{fileId}/versions/{versionNo}/preview                     预览元信息
GET /file/{fileId}/versions/{versionNo}/pages/{pageNo}/preview      该页图片（PNG）
GET /admin/files/{fileId}/versions/{versionNo}/preview               管理员入口
GET /admin/files/{fileId}/versions/{versionNo}/pages/{pageNo}/preview
```

与上一条「PDF 专用预览」的区别：本组接口接受**任意受支持格式**，内部按需转换。
两组都绑定明确版本并复用下载路径做归属校验，鉴权没有差异。

**用法**：先取元信息决定怎么渲染，再按页请求图片。

```json
// GET .../preview
{
  "fileId": 10, "versionNo": 2, "contentType": "…wordprocessingml.document",
  "extension": "docx",
  "pageCount": null, "requiresConversion": true, "previewable": true,
  "engine": "libreoffice-kit", "dpi": 110,
  "message": "页数将在首次渲染时确定"
}
```

| 字段 | 说明 |
| --- | --- |
| pageCount | 可预览页数；DOCX/XLSX 在首次渲染前为 `null`（避免「只看一眼元信息就把大文档排一遍版」） |
| requiresConversion | 是否需要外部渲染后端。PDF 与图片为 `false` |
| previewable | 是否具备预览条件 |
| engine | 实际引擎；PDF 与图片为 `native` |
| message | 不可预览或需提示时的可读说明，正常为 `null` |

**图片接口的响应**：
- 非图片版本返回 **PNG 字节流**，带响应头 `X-Image-Width` / `X-Image-Height`，
  建议据此刻预留高度避免布局跳动
- **图片版本原样回传原图**（保留原始格式与透明通道），此时 `Content-Type` 为该图真实类型
- 响应带 `Cache-Control: private, max-age=3600`；服务端按「内容摘要 + 页号 + DPI」缓存，
  同一版本重复请求不会重复渲染

**`dpi` 参数**：默认 110，允许 60–200，超出返回 400。分辨率越高越慢、内存占用越大。

**渲染后端未配置时**：元信息接口返回 `previewable=false` 与可操作提示（HTTP 200）；
图片接口返回 **503**。前端应展示 `message`/`msg`，而不是报「未知错误」。

### 8.2 文档结构读取（按格式）

```
GET /file/{fileId}/versions/{versionNo}/structure              DOCX 结构
GET /file/{fileId}/versions/{versionNo}/pdf/content            PDF 文本与页面类型
GET /file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview  单页渲染图
GET /file/{fileId}/versions/{versionNo}/xlsx/structure         XLSX 结构
GET /file/{fileId}/versions/{versionNo}/xlsx/range             XLSX 区域
GET /file/{fileId}/versions/{versionNo}/xlsx/formula-results   XLSX 公式计算结果
GET /file/{fileId}/versions/{versionNo}/header-footer-links   DOCX 分节页眉页脚链接状态
GET /file/{fileId}/versions/{versionNo}/footnotes             DOCX 正文脚注与引用位置
GET /file/{fileId}/versions/{versionNo}/table-of-contents     DOCX TOC 域与更新标记
POST /file/{fileId}/versions/{versionNo}/ocr                  PDF 扫描页或图片云端 OCR
POST /file/{fileId}/versions/{versionNo}/ocr/text             OCR 结果下载为纯文本
```

**DOCX 结构** `DocxStructureVO`：

```json
{
  "fileId": 10, "versionNo": 3,
  "blocks": [
    { "blockId": "p-1", "type": "PARAGRAPH", "text": "…", "styleId": "…" }
  ]
}
```

`blockId` 是稳定标识，**后续所有编辑操作都以它定位目标块**。前端必须原样回传，不要自己重排。

**PDF 内容** `PdfContentVO`：包含 `documentType` 与 `pages[]`，每页有 `pageNo`、`type`（文本型/扫描型）、`text`、字数。
扫描页的 `text` 为空，可把明确版本和页码提交到 `POST /file/{fileId}/versions/{versionNo}/ocr`：

```json
{"language":"zh+en","pages":[2,3],"dpi":200,
 "enhance":true,
 "region":{"x":40,"y":80,"width":900,"height":600}}
```

响应 `pages[]` 的 `source` 为 `NATIVE`、`OCR` 或 `EMPTY`；OCR 页返回完整页面宽高、区域、整页文字、平均置信度、`needsReview`、启发式 `paragraphs[]`、经校验的 `tables[]` 和按阅读顺序排列的 `lines[]`。每行和表格单元格包含完整页面坐标及置信度；低于阈值的项应进入人工复核。`enhance=true` 会转灰度并拉伸对比度但不改坐标；`paragraphs[]` 只用于复制或文本导出。`tables[]` 仅在云端明确返回且完整覆盖、不重叠、跨度合法时出现，否则错误结构返回 502，前端不要自行猜测表格。传入 `region` 时只上传该矩形，但仍返回完整页面坐标；未传区域时，PDF 有原生文字的页直接返回，不发送云端。云 OCR 默认关闭且只使用管理端启用、设为默认的 `scope=OCR` 模型；启用后页面会上传云端，界面必须提示数据外发风险。`POST .../ocr/text` 用相同请求返回带换页符的 UTF-8 文本附件。原生 PDF 结果、失败码和配置见 [API.md 第 3.11.1 节](../API.md#3111-云端-ocr)。

**PDF 页面预览**返回**图片字节流**，不是 JSON。前端用 `URL.createObjectURL` 渲染。
页数有限制，超大 PDF 只会渲染部分页。

文字型 PDF 可调用 `POST /file/{fileId}/versions/{versionNo}/pdf/convert-to-docx` 生成新版本：

```json
{"mode":"LAYOUT","instruction":"按版式优先重建","ocrLanguage":"zh+en"}
```

`EDITABLE` 适合继续编辑，`LAYOUT` 尽量保留文字横向位置、字号、粗斜体和基础行距。路径版本必须是当前版本，成功后返回 `Version`，可用新 versionNo 读取 DOCX 结构、继续人工或 AI 编辑。不传 `ocrLanguage` 时，含扫描页的转换仍返回 422；传入后扫描页走云端 OCR 再重建文字，模型不可用返回 503。复杂表格/域/脚注、精确分页和 OCR 真实质量仍需提示限制，不使用整页扫描图冒充可编辑内容。

DOCX 页眉页脚链接使用同一路径的 GET/POST：

```json
{"sectionNo":2,"location":"HEADER","type":"DEFAULT",
 "linkedToPrevious":false,"text":"第二分节页眉"}
```

取消链接时 `text` 必填，链接时必须为空。第一节不能链接上一节；重复设置相同状态返回 409。只在确实继承/拥有显式引用的状态下操作，避免覆盖复杂 PAGE、图片或表格内容。

正文脚注 GET 返回被引用的普通脚注及 `referencedBy` 块 ID。POST 使用 `operations[]` 执行 ADD/UPDATE/DELETE，分别需要目标正文段落、脚注 ID 和脚注文字；成功后一次生成一个版本。多段落或含表格等复杂脚注不会通过纯文字 UPDATE 强行覆盖。

TOC POST 可把复杂域插到文档开头或末尾，支持 1～9 级标题和超链接。已有 TOC 时不重复创建，只设置打开文件时更新域；最终标题、页码和分页必须在 Word/LibreOffice 打开后复核。

**XLSX 结构**包含 `sheets[]`（含 `firstRow`/`lastRow`）、`cells[]`、`styles[]`、`charts[]`。
单元格含 `address`、`type`、`value` 等；**读取单元格数量有上限**，超大表只返回部分。

**XLSX 公式结果**：`results[]` 每项含 `address`、`formula`、`status`、计算值。
后端用 POI 即时计算**支持的公式**；动态数组、溢出引用、结构化表引用、复杂 1904 日期公式及不支持的函数会返回明确的"需在办公软件中重算"提示；1904 工作簿的纯数值公式可直接计算，
**前端要把这个提示展示出来，不要显示成计算失败**。

### 8.3 DOCX 编辑

```
POST /file/{fileId}/versions/{versionNo}/paragraph-text       替换段落文本
POST /file/{fileId}/versions/{versionNo}/paragraph-edits      插入/删除段落
POST /file/{fileId}/versions/{versionNo}/table-edits          编辑表格
POST /file/{fileId}/versions/{versionNo}/paragraph-formats    设置标题/正文/列表格式或段前分页
POST /file/{fileId}/versions/{versionNo}/inline-images        插入图片
```

统一请求结构（以段落替换为例）：

```json
{
  "instruction": "把第二段改得更正式",
  "operations": [
    { "blockId": "body/p2", "text": "新的段落文本" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| instruction | 操作说明，会写入版本的 `instruction` 字段，便于版本历史展示 |
| operations | 操作列表；每个操作的字段随接口不同 |

各接口的 `operations` 字段：

| 接口 | 操作字段 |
| --- | --- |
| paragraph-text | `blockId`、`text` |
| paragraph-edits | `instruction`、`operations[].type`（插入/删除）、`blockId`、`text` |
| table-edits | `type`、`blockId`、`endBlockId`（合并时使用）、`text`、`values`、`dimensionTwips`、`repeatHeader` |
| paragraph-formats | `type`、`blockId`、`level`（标题层级） |
| inline-images | `blockId`、`imageFileId`、`imageVersionNo`、`widthPixels`、`heightPixels` |

**响应统一返回新建的版本 `Version`**，前端据此刷新版本列表与预览。

paragraph-formats 新增 SET_PAGE_BREAK_BEFORE / CLEAR_PAGE_BREAK_BEFORE，只用于 body/pN 正文顶层段落，不传 level。清除操作显式关闭段前分页，不移除手工分页符或分节设置；表格内段落返回 422。结构的 pageBreakBefore 为 true/false/null，null 表示未直接设置、可能继承样式，不应显示为确定关闭。插入段落不复制参照段落的直接分页属性，其他样式仍可能影响实际分页；保存后用新版本预览核对。

结构查询的 blocks 末尾现在可能包含 HEADER/FOOTER 容器，children 为页眉页脚中的段落、表格及图片；正文原有 body 路径不变。例如 `header0/p1`、`footer0/t0/r0/c0/p0` 可通过 paragraph-text 替换普通段落。N 是该版本的部件索引，不是页码或节号；跨节共享同一页眉页脚时，修改会作用于所有引用位置，提交前应在界面说明。

页眉页脚支持普通文字替换和分节链接/独立设置；正文脚注与 TOC 域另有专门接口。页码域、书签、图片、超链接、内容控件等复杂段落替换返回 422；不能把“能读出文字”当作“可以整段编辑”。段落插入不会复制分节设置，含分节设置的段落不能直接删除。表格支持行高、整列宽度、重复标题行，以及规则矩形单元格的合并/拆分；列宽遇到横向合并网格返回 422。`MERGE_CELLS` 用 `blockId` 和 `endBlockId` 标记左上/右下单元格，`SPLIT_CELL` 只指向该表格唯一合并区域的左上单元格；合并或拆分不能与同一表格的其他编辑同批提交。合并会把非空纯文字按行列顺序保留到左上单元格，拆分恢复空单元格；复杂内容返回 422，跨表、逆序及一乘一区域返回 400。编辑成功后按新版本重新读取结构与预览，既有版本比较也会返回页眉页脚文字差异。详细规则见 [API.md 第 4.3～4.11 节](../API.md#43-读取-docx-结构)。

**约束**：部分操作会被拒绝（如"删除表格中唯一的行"、"替换含图片的段落"），
返回 `400` 或 `422`，`msg` 会说明原因。前端应把 `msg` 原样展示。

### 8.4 XLSX 编辑

```
POST /file/{fileId}/versions/{versionNo}/xlsx/cell-edits        单元格读写
POST /file/{fileId}/versions/{versionNo}/xlsx/structure-edits   行列增删
POST /file/{fileId}/versions/{versionNo}/xlsx/sheet-edits       工作表增删改名
POST /file/{fileId}/versions/{versionNo}/xlsx/data-edits        排序与筛选
POST /file/{fileId}/versions/{versionNo}/xlsx/formats           基础格式
POST /file/{fileId}/versions/{versionNo}/xlsx/images            内嵌已有图片的明确版本
```

请求顶层使用 `instruction` 与 `operations` 数组；`sheetName`、`cell`、`range` 等位于各操作对象内。`cell-edits` 的 `recalculateFormulas` 是顶层选项。

各接口特有字段：

| 接口 | 特有字段 |
| --- | --- |
| cell-edits | `type`（SET_VALUE/SET_FORMULA/CLEAR/SET_RANGE/FILL_FORMULA）、`cell` / `range`、`valueType`（STRING/NUMBER/BOOLEAN/DATE）、`value`、`formula`、`values` |
| structure-edits | `type`（插入/删除行或列）、`start`、`count` |
| sheet-edits | `type`（新增/重命名/删除）、`sheetName`、`newName`、`position` |
| data-edits | `type`（排序/筛选）、`range`、`keyColumn`、`direction`、`hasHeader` |
| formats | `range`、`fontName`、`fontSize`、`bold`、`italic`、`fontColor`、`horizontalAlignment`、`verticalAlignment`、`wrapText`、`fillColor`、`numberFormat`、`borderStyle` |
| images | `sheetName`、`cell`、`imageFileId`、`imageVersionNo`、`widthPixels`、`heightPixels`；无需 type |

**注意文本与公式**：SET_VALUE 的 STRING 始终写为普通文本，即使以等号开头。公式应显式使用 SET_FORMULA 或 FILL_FORMULA 并传 formula；SET_RANGE 矩阵中的公式才使用 valueType=FORMULA。界面应提供明确的值类型选择。

XLSX 插图每批 1～20 张，尺寸均必填且为 1～10000 像素，以目标单元格左上角定位，不覆盖单元格内容。选择图片时保存其内部 ID 与明确版本，成功后按返回的新版本预览/下载。不要传 URL、路径或省略版本；该入口直接保存人工编辑，无 AI 确认步骤。参数、来源记录和 400/404/409/413/422 边界见 [API.md 第 3.21 节](../API.md#321-将已有图片插入-xlsx)。

## P9 版本历史页

见 8.1 的接口。前端建议展示为时间线，每项显示：

- `versionNo` 与 `changeType` 的图标（上传/AI 编辑/人工编辑/转换/恢复）
- `instruction`（产生该版本的自然语言指令）
- `resultSummary`（结果说明）
- `createTime`、`sizeBytes`
- 操作：预览、下载、**恢复到此版本**

### 9.1 修改前后比较

```
GET /file/{fileId}/versions/{fromVersion}/compare/{toVersion}
```

响应 `data`：

```json
{
  "fileId": 10, "fromVersion": 1, "toVersion": 2, "format": "DOCX",
  "truncated": false,
  "changes": [
    { "type": "MODIFIED", "key": "body/p1", "label": "段落 body/p1",
      "before": "旧内容", "after": "新内容", "kind": "PARAGRAPH" },
    { "type": "ADDED", "key": "body/p2", "label": "段落 body/p2",
      "before": null, "after": "新增段落", "kind": "PARAGRAPH" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| type | `ADDED` / `REMOVED` / `MODIFIED` |
| key | 定位标识。DOCX 为块 ID（如 `body/p1`、`body/t0/r1/c0`），XLSX 为 `工作表!单元格`（如 `Sheet1!A1`） |
| label | 面向人的位置描述，可直接展示 |
| before | 改动前内容；`ADDED` 时为 `null` |
| after | 改动后内容；`REMOVED` 时为 `null` |
| kind | 块类型：`PARAGRAPH` / `HEADING` / `CELL` / `IMAGE`，便于按类型显示图标 |
| truncated | `true` 表示因超出读取上限而未比较完，**此时 `changes` 不是完整结论**，界面必须提示 |

**比较粒度是块/单元格而不是字符**。用户关心的是「第二段被改了」，
字符级 diff 会把表格与段落改动渲染成一串难以阅读的增删碎片。
代价是段落内的小改动会显示为整段变化 —— 这是刻意的取舍。

**两个版本都必须显式指定**，没有「和当前版本比」的语义，否则用户看到的差异会随别人编辑而改变。
两个版本都经下载路径校验归属，因此不能用「自己的旧版本 vs 他人的新版本」探测他人内容。

**注意 `truncated`**：XLSX 结构的单元格读取有上限，超大表的比较结果可能不完整。

### 9.2 导出 PDF

```
GET /file/{fileId}/versions/{versionNo}/export/pdf
```

返回 **PDF 字节流**（不是统一 JSON），用 `response.blob()` 处理。

- 该版本本身已是 PDF 时直接返回原内容，不做无意义的再转换
- 其余格式经渲染后端转换，因此**未配置渲染后端时返回 503**，前端应展示该提示
- 响应头里的文件名已把原扩展名替换为 `.pdf`（如 `报告.docx` → `报告.pdf`），便于与原文件配对
- 支持转换的格式：`docx`、`xlsx`、`pptx`（另有 `odt`/`ods`/`odp`/`doc`/`xls`/`ppt`）。
  **其他格式返回 400 并说明支持范围**，而不是送到后端再失败

### 9.3 应用级兼容性验收

```
POST /file/{fileId}/versions/{versionNo}/compatibility-check
```

让办公软件真正打开并排版该版本，检查能否渲染、页数、缺失字体与页面边缘裁切。

```json
{
  "fileId": 10, "versionNo": 2, "compatible": true,
  "engine": "libreoffice-kit", "pageCount": 2, "renderedPages": 2,
  "missingFonts": ["Courier"],
  "issues": [
    { "severity": "WARNING", "code": "MISSING_FONTS",
      "message": "以下字体缺失并被替换，版式可能与预期不同：Courier" }
  ]
}
```

| 问题代码 | 级别 | 含义 |
| --- | --- | --- |
| `NO_PAGES` | ERROR | 可解析但排不出任何页面 |
| `NO_RENDERED_PAGE` | ERROR | 未产生页面图片，渲染流程异常 |
| `PAGES_TRUNCATED` | WARNING | 页数超出检查上限，只检查了前若干页 |
| `MISSING_FONTS` | WARNING | 字体缺失被替换，版式可能不同 |
| `CONTENT_NEAR_BORDER` | WARNING | 页面边缘存在内容，可能被裁切 |

**只有 ERROR 会使 `compatible=false`**；WARNING 表示「可用但需人工确认」。
本接口仅支持 DOCX，其他格式返回 400。

**该接口耗时可观**（需要真正排版），建议前端触发时显示进度提示，不要在页面加载时自动调用。

## P10 图片基础处理

仅保留本地格式转换、尺寸调整、裁剪、直角旋转、亮度及单行文字水印。图片生成、智能修图、背景/对象替换和蒙版不在范围；PDF/图片 OCR 使用独立的云端文字识别入口。

```
POST /file/{fileId}/versions/{versionNo}/image-edits
```

```json
{
  "instruction": "裁剪、旋转、提亮并添加标题",
  "operations": [
    {"type": "CROP", "x": 0, "y": 0, "width": 800, "height": 600},
    {"type": "ROTATE", "degrees": 90},
    {"type": "BRIGHTNESS", "factor": 1.2},
    {"type": "ADD_TEXT", "text": "项目周报", "x": 10, "y": 10, "fontSize": 24, "color": "#112233"}
  ],
  "outputFormat": "JPEG",
  "jpegQuality": 0.9
}
```

| 字段 | 说明 |
| --- | --- |
| operations | 最多 20 项，按顺序执行；字段放在各操作对象内 |
| operations[].type | CROP / RESIZE / ROTATE / BRIGHTNESS / ADD_TEXT |
| x, y, width, height | CROP 坐标与尺寸；RESIZE 只使用 width/height |
| degrees | ROTATE 使用，顺时针 90/180/270 |
| factor | BRIGHTNESS 使用，0～4，1 为原亮度，保留 alpha |
| text, x, y | ADD_TEXT 使用；单行纯文本最多 500 字符，x/y 为文字实际像素边界的左上角 |
| fontSize, color | ADD_TEXT 可选，字号 1～512（默认 24）、#RRGGBB（默认黑色） |
| outputFormat | 输出格式；`PNG` 保留透明通道，`JPEG` 合成白色背景 |
| jpegQuality | JPEG 质量 |

响应：新建的版本 `Version`。

**前端注意**：显示坐标须换算为对应操作执行时图片的像素坐标，前面的裁剪/缩放/旋转会改变后续坐标和边界。文字越界返回 400，不会自动裁字；系统缺少对应字体返回 422。PNG 保留透明度，JPEG 合成白底，jpegQuality 仅能用于 JPEG 输出。失败不生成部分版本；完整参数及边界见 [API.md 第 3.20 节](../API.md#320-本地编辑-pngjpeg-图片)。

## P11 DOCX 生成

```
POST /file/docx/generate
```

```json
{
  "fileName": "新文档.docx",
  "title": "文档标题",
  "instruction": "生成一份季度总结",
  "blocks": [
    { "type": "HEADING", "text": "第一章", "level": 1 },
    { "type": "PARAGRAPH", "text": "正文内容" },
    { "type": "IMAGE", "imageFileId": 12, "imageVersionNo": 1, "widthPixels": 400, "heightPixels": 300 }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| fileName | 生成文件的展示名 |
| blocks[].type | 块类型：标题/段落/图片等 |
| blocks[].level | 标题层级，仅 HEADING 使用 |
| blocks[].imageFileId | 引用**自己的**文件 ID；引用他人的会被拒 |

响应：新建的 `FileAsset`（`source` 为 `GENERATED`）。

模板模式先调用登录可用的 `GET /templates`，`data` 是已启用且已有内容版本的 DOCX 模板数组。普通用户使用此入口；`GET /admin/templates` 仍仅供管理员管理。将列表中 `variables` JSON 字符串解析为表单定义：

```json
[
  { "name": "title", "label": "标题", "required": true, "description": "文档标题" }
]
```

`name` 是占位符标识（只允许字母数字下划线、不能以数字开头），`label` 用于界面显示。

模板正文/表格使用 `{{name}}`。提交同一个生成入口：

```json
{
  "templateId": 11,
  "templateVersion": 3,
  "fileName": "本周周报.docx",
  "variables": {"title": "本周进展"}
}
```

`templateVersion` 必须使用所选模板当前 versionNo，模板模式不传 title 或 blocks。变量只提交纯文本字符串；必填变量不能省略、null 或全空白，可选未填置空字符串。未知变量拒绝，单值最多 10000 字符。支持跨文字片段占位符，不支持循环、图片变量、页眉页脚等位置或复杂段落中的变量。

成功后得到独立的 GENERATED 文件和版本 1，进入文件页复用已有按版本预览/下载。409 表示模板内容或变量定义发生更新，重新获取列表并让用户确认填写；404 表示模板缺失或停用，422 表示模板结构不支持或损坏，预览后端不可用返回 503。完整限制见 [API.md 第 4.2 节](../API.md#42-按结构生成-docx)。

---

# 三、异步任务

## P12 任务列表页

```
GET /task/list?page=1&size=20&status=&taskType=&fileId=
GET /task/by-file/{fileId}        某文件的处理历史（不分页）
```

响应 `data`：`PageResult<Task>`

```json
{
  "records": [{
    "id": 100, "fileId": 10, "taskType": "CONVERT", "status": "RUNNING",
    "progress": 40, "currentStep": "解析请求",
    "instruction": "转成 DOCX", "errorCode": null, "errorMessage": null,
    "inputVersion": 1, "outputVersion": null,
    "retryCount": 0, "maxRetry": 2, "cancelRequested": false,
    "createTime": "…", "startTime": "…", "finishTime": null,
    "elapsedMs": 3200
  }],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

`elapsedMs` 由服务端计算：已结束按 start→finish，未结束按 start→当前时间。

## P13 任务详情页

```
GET  /task/{taskId}              含步骤列表
POST /task/{taskId}/cancel       取消
POST /task/{taskId}/retry        重试
```

详情响应 `data`：

```json
{
  "task": { "...同上 Task..." },
  "steps": [
    { "stepNo": 1, "stepName": "解析请求", "status": "SUCCEEDED",
      "detail": "任务类型=CONVERT", "errorMessage": null,
      "startTime": "…", "finishTime": "…", "durationMs": 120 }
  ]
}
```

**取消语义**：

- `PENDING` 任务 → 立即置为 `CANCELLED`
- `RUNNING` 任务 → 只置 `cancelRequested=true`，由执行循环在**步骤边界**自行收尾
- 已进入终态的任务 → 幂等返回当前状态，不报错

**重试语义**：只允许重试 `FAILED` 或 `CANCELLED` 的任务；`SUCCEEDED` 会返回 `409`。
已停用的 IMAGE_GEN 是例外：所有状态都拒绝重试并返回业务码 400，只提供历史查询和取消。
重试会**新建一个任务**，原任务记录保留（便于排查失败原因）。
重试次数达上限返回 `429`。

### 进度更新方式

任务进度**只有轮询**：`GET /task/{taskId}`。

建议：任务处于 `PENDING`/`RUNNING` 时每 2 秒轮询一次，进入终态后停止。
任务列表页同理，但有列表时可放宽到 5 秒。

AI 回复文本有独立 SSE 接口；异步任务仍不支持 SSE，继续使用轮询。

### 任务提交（当前主要由后端流程调用）

```
POST /task
```

```json
{
  "taskType": "EXPORT",
  "fileId": 10,
  "inputVersion": 3,
  "instruction": "导出为 PDF",
  "idempotencyKey": "前端生成的 UUID",
  "expectVersion": 3,
  "timeoutSeconds": 600
}
```

| 字段 | 说明 |
| --- | --- |
| taskType | `CONVERT`/`EXPORT`；`OCR`、`AI_EDIT`、`IMAGE_GEN` 仅历史展示/查询 |
| fileId | 必填，必须是自己的文件 |
| inputVersion | 不传表示使用当前最新版本，不得超过最新版本 |
| idempotencyKey | **幂等键**：同一用户同一键只会创建一个任务，前端可安全重试提交 |
| expectVersion | 兼容字段；真实处理器以 inputVersion 做版本冲突判断 |
| timeoutSeconds | 10-3600，不传取服务端默认 |

IMAGE_GEN 只保留历史展示和筛选，不展示新建或重试操作；旧排队记录可能以 FAILED / TASK_TYPE_DISABLED 收尾。已有图片通过 P10 的同步基础处理接口操作，不创建图片生成任务。

**幂等键用法**：为"一次用户意图"生成一个 UUID，重试提交时沿用同一个键。
命中时后端直接返回已有任务，**不会重复执行**。

---

# 四、AI 对话

## P14 会话列表页

```
GET    /ai/sessions?page=1&size=20&status=&fileId=&keyword=
POST   /ai/sessions                  新建
GET    /ai/sessions/{sessionId}      详情
POST   /ai/sessions/{sessionId}/archive   归档
DELETE /ai/sessions/{sessionId}      删除
```

新建请求：

```json
{ "title": "润色报告", "fileId": 10 }
```

`fileId` 可选；**传入时会校验该文件属于当前用户**，传他人的会返回 404。

会话响应 `data`：`AiSession`

```json
{
  "id": 7, "userId": 1, "fileId": 10, "title": "润色报告",
  "status": "ACTIVE", "createTime": "…", "updateTime": "…"
}
```

归档后**不再接收新消息**（发消息返回 `409`）。

## P15 对话详情页

```
GET  /ai/sessions/{sessionId}/messages
POST /ai/sessions/{sessionId}/messages    { "content": "把第二段改正式一点" }
```
消息响应 `data`（发消息时返回新建的那条）：

```json
{
  "id": 88, "sessionId": 7, "role": "USER", "content": "…",
  "toolName": null, "toolArgs": null, "toolResult": null,
  "modelName": null, "promptTokens": null, "completionTokens": null,
  "durationMs": null, "createTime": "…"
}
```

消息列表按时间正序返回。`role` 取值 `USER`/`ASSISTANT`/`TOOL`/`SYSTEM`。

旧 `POST .../messages` 仍只记录用户消息。需要模型回复时直接调用新增接口，不要先通过旧接口重复保存：

```text
POST /ai/sessions/{sessionId}/replies
POST /ai/sessions/{sessionId}/replies/stream
GET  /ai/sessions/{sessionId}/replies/{messageId}
POST /ai/sessions/{sessionId}/replies/{messageId}/confirm
POST /ai/sessions/{sessionId}/replies/{messageId}/discard
```

发送体：`{"content":"把第二段改正式一点","versionNo":1,"idempotencyKey":"客户端生成的稳定键","sources":[{"title":"产品说明","url":"https://example.test","excerpt":"待核验摘录"}]}`。`sources` 可有 0～5 条，只能是纯数据文本；服务端会回显来源并给出 `verificationNotice`，不表示已联网核验。绑定文档支持 DOCX 编辑，以及 XLSX 公式、新表格和基础图表，versionNo 必填；纯对话不传版本。旧会话 CRUD 不变。

返回 `AiReply`，包含助手 `message`、`runStatus`、`planStatus`、`sourceVersion`、`outputVersion`、过程与用量 `result`、确认前逐项内容差异 `changes`。完整字段和状态规则见 [API.md 第 8 节](../API.md#8-ai-回复与确认编辑)。

界面流程：发送时显示同步请求状态；超时重发沿用同一幂等键，若返回 RUNNING 则通过 GET 查询。PENDING 时显示 before/after、操作类型和理由，再提供确认/放弃；确认和放弃均无需请求体。CONFIRMED 时按 outputVersion 预览、比较和下载。FAILED 不代表编辑成功；stub 模型和未配置计价必须明确展示，不能显示真实模型完成或免费。放弃后可用新键继续发送调整要求。

`/replies/stream` 返回 `text/event-stream;charset=UTF-8`：`started` 给出 replyId，`delta` 携带真实模型文本增量，`completed` 携带完整 AiReply，`error` 携带统一错误对象。断线可使用 started 中的 replyId 调 GET 恢复最终状态。模型不支持流式时，会在完成后退回一次完整 delta，协议不变。确认前提供内容差异，页面排版预览仍使用已保存版本；真实模型与前端端到端联调待验收。

插图也使用相同确认流程：`changes[].type=INSERT_IMAGE` 时，读取其 `image` 对象中的 `fileId/versionNo/widthPixels/heightPixels`，通过既有按版本图片预览展示所选图片，显示“追加到目标段落”及尺寸。该项 before/after 为 null，不代表删除。图片参数同样持久化在 result.plans 中，客户端不重传计划；确认时服务端再次校验图片归属和版本。完整结构见 [API.md 第 8.3 节](../API.md#83-待确认的-docx-图片插入)。旧文本项的 image 可缺失或为 null。

`changes[].type=FORMAT_PARAGRAPH` 时，使用 `beforeFormat`（原 styleId、headingLevel、numberingFormat、pageBreakBefore）与目标 `format` 展示格式差异；before/after 为 null，不代表删除。支持标题 1～9、正文、项目符号、编号、清除列表，以及正文顶层段落的段前分页设置/取消。pageBreakBefore 为 null 或旧响应没有该字段时，表示未直接设置、可能继承样式；不能解释为确定关闭。客户端不提交格式计划，确认后用新版本预览核对分页。字段见 [API.md 第 8.4 节](../API.md#84-待确认的-docx-基础格式)。

每日额度按 ChatModel 调用尝试计数，包含智能体重试。生成回复的业务码 429（额度用尽）或 503（无法检查额度）仍可能带 `data=AiReply`，应先保存 message.id 和失败状态，再展示 msg；禁止确认或自动换键重试。GET 查询以业务码 0 返回同一 FAILED 记录；原键重放仍返回原失败结果。额度恢复后由用户用新键发送。规则见 [API.md 第 8.5 节](../API.md#85-每用户每日调用额度)。

XLSX 会话通过相同接口提出公式要求。`changes[].type=SET_FORMULA` 时展示目标工作表/单元格、before、after 与 reason；原公式和目标公式带等号，普通原值为文本，空白目标 before 为 null，不表示删除。target 是内部“原始工作表名!A1”定位，最后一个 ! 为分隔符，不额外给工作表名加 Excel 引号。用户确认前不写 XLSX；确认后按 outputVersion 预览或调用公式结果接口，不能把公式文本视为已计算的数值结果。当前不支持 AI 区域填充或行列调整；完整规则见 [API.md 第 8.6 节](../API.md#86-待确认的-xlsx-单元格公式)。

`changes[].type=CREATE_TABLE` 时 `before` 为 null，`after` 展示新表列数、行数以及是否附带图表；`CREATE_CHART` 的 `after` 展示图表类型、分类区域和数值区域。两类计划都只能由服务端工具生成，客户端确认时仍只提交空请求体，不能回传或修改 JSON 计划。确认后一次生成一个 AI_EDIT 版本，可按 versionNo 读取新工作表/图表或预览。限制和协议见 [API.md 第 8.7 节](../API.md#87-待确认的-xlsx-新表格与基础图表)。

---

# 五、管理端（仅 ADMIN）

所有 `/admin/**` 接口在类级别要求 `ADMIN` 角色，非管理员返回 `403`。

## P16 概览统计页

```
GET /admin/statistics/overview
```

```json
{
  "totalUsers": 12, "enabledUsers": 11, "disabledUsers": 1, "adminUsers": 2,
  "activeFiles": 30, "deletedFiles": 3, "storageBytes": 104857600,
  "totalTasks": 50, "pendingTasks": 1, "runningTasks": 2,
  "succeededTasks": 40, "failedTasks": 7, "cancelledTasks": 0,
  "taskFailureRate": 0.1489
}
```

`taskFailureRate` 是**小数**（0.1489 = 14.89%），前端需自行乘 100 加百分号。

## P17 用户管理页

```
GET  /admin/users?page=&size=&keyword=&status=&role=
GET  /admin/users/{userId}
GET  /admin/users/{userId}/usage
PUT  /admin/users/{userId}/status    { "status": "ENABLED" | "DISABLED" }
PUT  /admin/users/{userId}/role      { "role": "USER" | "ADMIN" }
```

用户响应 `data`：`AdminUser`

```json
{
  "id": 1, "username": "user_01", "phone": "13800000000",
  "role": "USER", "status": "ENABLED", "balance": 5.000000,
  "createTime": "…", "updateTime": "…", "lastLoginTime": "…"
}
```

用量响应 `data`：`AdminUserUsage`（含 `activeFiles`、`storageBytes`、各状态任务数与 `taskFailureRate`）

### 两条会返回 409 的业务规则（前端必须处理）

1. **管理员不能禁用自己的账号** → `409 管理员不能禁用自己的账号`
2. **管理员不能降低自己的角色** → `409 管理员不能降低自己的角色`

并发修改时还可能返回 `409 账号状态已发生变化，请刷新后重试`（乐观锁）。

## P18 文件管理页

```
GET    /admin/files?page=&size=&keyword=&status=
GET    /admin/files/{fileId}
GET    /admin/files/{fileId}/download
PUT    /admin/files/{fileId}/name
DELETE /admin/files/{fileId}
```

结构与用户侧一致，但响应是 `AdminFile`（**多一个 `userId` 字段**，用于显示归属）。

管理员还可查看任意用户的版本与结构（路径与用户侧相同，前缀换成 `/admin`）：

```
GET /admin/files/{fileId}/versions
GET /admin/files/{fileId}/versions/{versionNo}
GET /admin/files/{fileId}/versions/{versionNo}/download
GET /admin/files/{fileId}/versions/{versionNo}/structure
GET /admin/files/{fileId}/versions/{versionNo}/pdf/content
GET /admin/files/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview
GET /admin/files/{fileId}/versions/{versionNo}/xlsx/structure
GET /admin/files/{fileId}/versions/{versionNo}/xlsx/range
GET /admin/files/{fileId}/versions/{versionNo}/xlsx/formula-results
```

> 管理员查看用户文档内容会**写入审计日志**。前端可在页面上提示"本次查看已记录"。

## P19 任务管理页

```
GET  /admin/tasks?page=&size=&status=&taskType=&fileId=
GET  /admin/tasks/{taskId}
POST /admin/tasks/{taskId}/cancel
POST /admin/tasks/{taskId}/retry
```

响应为 `AdminTask` / `AdminTaskDetail`（结构同用户侧，**多 `userId`**）。

**失败任务查看**：`GET /admin/tasks?status=FAILED` 即列出全部失败任务，
详情里的 `errorCode` 与 `errorMessage` 用于定位原因。

## P20 模型配置页

```
GET    /admin/model-configs?page=&size=&scope=&status=&keyword=
GET    /admin/model-configs/{id}
POST   /admin/model-configs
PUT    /admin/model-configs/{id}
PUT    /admin/model-configs/{id}/status    { "status": "ENABLED" | "DISABLED" }
DELETE /admin/model-configs/{id}
```

响应 `data`：`ModelConfig`

```json
{
  "id": 1, "code": "default-text", "name": "默认文本模型",
  "scope": "TEXT", "provider": "openai-compatible",
  "baseUrl": "https://…", "modelName": "qwen-plus",
  "timeoutSeconds": 60, "maxOutputTokens": 2048, "temperature": 0.70,
  "status": "DISABLED", "isDefault": true, "remark": "…",
  "createTime": "…", "updateTime": "…",
  "apiKeyConfigured": true,
  "apiKeyMasked": "sk-a****z9x1"
}
```

创建/更新请求：

```json
{
  "code": "default-text", "name": "默认文本模型", "scope": "TEXT",
  "provider": "openai-compatible", "baseUrl": "https://…", "modelName": "qwen-plus",
  "apiKey": "sk-…", "timeoutSeconds": 60, "maxOutputTokens": 2048,
  "temperature": 0.7, "isDefault": true, "remark": ""
}
```

> ### 密钥是"只写不读"的，前端设计必须配合
>
> 1. **响应里没有密钥明文**，只有 `apiKeyConfigured`（布尔）与 `apiKeyMasked`（脱敏提示）
> 2. **编辑时 `apiKey` 留空表示"保持原密钥不变"**。因此**不能做成"回填密钥"的输入框**，
>    否则用户以为看到的是原值、实际提交空串会意外清空，或者反过来以为没改实际改了
> 3. 正确做法：输入框占位符显示 `apiKeyMasked`（如 `sk-a****z9x1`），
>    并明确提示"留空则不修改密钥"
> 4. **密钥在数据库中是加密存储的**（AES-256-GCM），因此没有任何接口能取回明文

### 三条会返回 409 的保护规则

| 操作 | 拒绝原因 |
| --- | --- |
| 启用未设密钥的配置 | `409 该配置尚未设置 API Key，无法启用` |
| 删除默认配置 | `409 该配置是 <scope> 用途的默认配置，请先指定其他默认配置再删除` |
| 删除启用中的模板 | `409 请先停用该模板再删除` |

**新建的配置状态固定为 `DISABLED`**，需要显式调用启停接口启用。
这是刻意的——密钥没验证就被任务自动选中会直接导致调用失败。

## P21 模板管理页

```
GET    /admin/templates?page=&size=&enabled=&category=&keyword=
GET    /admin/templates/{id}
POST   /admin/templates                       multipart：元数据 + file
PUT    /admin/templates/{id}                  更新元数据（JSON）
POST   /admin/templates/{id}/content          multipart：替换内容，versionNo +1
PUT    /admin/templates/{id}/enabled?enabled=true
DELETE /admin/templates/{id}
GET    /admin/templates/{id}/download
GET    /admin/templates/{id}/versions?page=&size=
GET    /admin/templates/{id}/versions/{versionNo}/download
POST   /admin/templates/{id}/versions/{versionNo}/restore?expectVersion=当前内容版本号
```

响应 `data`：`DocTemplateVO`（列表接口为分页包装，不返回内部 storageKey）

```json
{
  "id": 1, "code": "weekly-report", "name": "周报模板",
  "category": "报告", "description": "…",
  "originalName": "template.docx",
  "contentType": "…wordprocessingml.document", "extension": "docx",
  "sizeBytes": 10240, "versionNo": 2,
  "variables": "[{\"name\":\"title\",\"label\":\"标题\",\"required\":true}]",
  "enabled": false, "hasFile": true,
  "createTime": "…", "updateTime": "…"
}
```

**创建与替换内容用 `multipart/form-data`**，不是 JSON：

| 接口 | 表单字段 |
| --- | --- |
| POST /admin/templates | `code`、`name`、`category`、`description`、`variables`、`file` |
| POST /admin/templates/{id}/content | `file` |
| PUT /admin/templates/{id} | JSON body（`SaveDocTemplateDTO`，不含文件） |

**新建的模板默认 `enabled=false`**，需要显式启用后才对普通用户可见。

元数据 PUT 是完整替换：提交需要保留的全部元数据字段；category、description 为 null、空白或省略时清空，variables 为 null、空白、JSON null 或省略时清空。元数据保存不修改文件、enabled 或 versionNo，启停也不会清空元数据。并发删除/启用或记录变化导致写入失败时返回业务码 `409`，刷新后重试；删除在数据库执行时再次检查模板仍为停用状态。

**`variables` 是字符串形式的 JSON 数组**，前端需要二次 `JSON.parse`。
格式约束：最多 100 个变量；`name` 必须是字符串，去除首尾空格后保存，1～64 字符、只允许字母数字下划线且不能以数字开头。名称区分大小写，规范化后不得重复。`label`、`description` 可省略或为 null，非 null 时必须是字符串，分别最多 128、255 字符；`required` 可省略，提供时必须是 JSON 布尔值，不能传字符串、数字或 null。非法结构后端返回业务码 `400`。

创建或替换内容后操作失败、事务明确回滚，会清理本次新上传文件。文件历史列表按 versionNo 倒序分页（page=1、size=20，最多 100），包含 templateId、versionNo、originalName、contentType、extension、sizeBytes、changeType、restoredFromVersion、createTime，不含 storageKey 或变量定义。changeType 为 BASELINE / CREATE / REPLACE / RESTORE；只有恢复记录的 restoredFromVersion 有值。

恢复前先读取模板当前 versionNo，作为必填 expectVersion 提交。恢复只新增文件版本，原历史保留，变量、分类、名称和启停状态不变；可跨历史格式恢复。成功后使用响应中的新 versionNo 刷新页面；并发变化返回业务码 409，先刷新再处理，不要自动更换 expectVersion 重复恢复。缺失历史返回 404，文件缺失返回 500，不能回退为下载最新内容。历史下载和恢复仅限管理员并记录审计。

V6 仅回填已有模板的当前文件版本（BASELINE），保留原版本号，不补造更早历史；其 createTime 是建立索引时间。删除模板后历史接口不可再访问，底层文件与索引仍保留。启用的 DOCX 模板可由普通用户填变量生成，见 P11；生成文件复用按版本预览，管理端没有直接模板预览入口。正式存储回收尚未实现，现有后台任务只清理临时目录。

## P22 审计日志页

```
GET /admin/audit-logs?page=&size=&userId=&action=&targetType=&targetId=&result=&from=&to=&requestId=
```

响应 `data`：`PageResult<AuditLog>`

```json
{
  "records": [{
    "id": 7, "userId": 1, "username": "admin_snapshot",
    "action": "ADMIN_FILE_VIEW", "targetType": "FILE", "targetId": "10",
    "detail": "查看版本 2 的页面预览元信息", "result": "SUCCESS",
    "requestIp": "127.0.0.1", "requestId": "req-abc-123",
    "createTime": "2026-10-15T20:30:00"
  }],
  "total": 1, "page": 1, "size": 20, "pages": 1
}
```

| 参数 | 说明 |
| --- | --- |
| userId | 按操作者过滤 |
| action | 按动作过滤，见下表 |
| targetType | `USER` / `FILE` / `VERSION` / `TASK` / `MODEL_CONFIG` |
| targetId | 按资源 ID 过滤 |
| result | `SUCCESS` / `FAILURE` |
| from / to | 时间范围（ISO-8601 本地时间，含端点） |
| requestId | 按请求链路 ID 过滤 |

**为什么需要这一页**：审计记录此前是「只写不读」——有写入路径但没有查询入口。
实际后果是「管理员查看用户文档会留痕」这句话无法被验证、账号禁用与角色调整
事后无法追溯、写入字段是否完整也没有任何环节会发现。

**界面上必须体现的三个字段**：

- `username` 是**快照**，不是当前用户名。用户改名后旧记录仍显示当时的名字 ——
  这是刻意设计的，用于追溯，前端**不要**去关联当前用户表覆盖它
- `requestId` 可与后端日志对齐。用户报告某次操作异常时，用响应头里的
  `X-Request-ID` 能直接定位那次调用产生的全部记录
- `result` 为 `FAILURE` 的记录包含登录失败、越权尝试等，**建议默认提供
  「只看失败」的筛选**，这是审计最主要的用途之一

`detail` 在写入时已脱敏（不含密码、密钥、模型 Key 或文件正文），可安全展示。

**建议的快捷筛选**：按动作、只看 `FAILURE`、按时间倒序（后端已固定按时间倒序，无需前端排序）。

---

# 六、前端实现建议

## 6.1 建议的路由结构

```
/login                  登录（密码 / 验证码 Tab）
/register               注册
/reset-password         找回密码
/                       工作台首页（概览）
/files                  文件列表
/files/upload           上传
/files/:id              文件详情（按格式切换预览组件）
/files/:id/versions     版本历史
/tasks                  任务列表
/tasks/:id              任务详情
/ai                     会话列表
/ai/:sessionId          对话详情
/profile                个人中心（改密 / 退出设备）
/admin                  概览统计
/admin/users            用户管理
/admin/files            文件管理
/admin/tasks            任务管理
/admin/model-configs    模型配置
/admin/templates        模板管理
```

## 6.2 文件详情页的组件拆分建议

```
FileDetailPage
├─ VersionSwitcher          版本切换（保证预览与下载同版本）
├─ PreviewPane              按 extension 分发
│    ├─ DocxPreview         结构树 + 编辑面板
│    ├─ XlsxPreview         表格网格 + 公式结果
│    ├─ PdfPreview          页面图片轮播
│    └─ ImagePreview        canvas + 编辑工具条
└─ VersionTimeline          版本历史
```

## 6.3 必须实现的公共能力

| 能力 | 说明 |
| --- | --- |
| **401 自动刷新** | 见 0.5，含互斥与只重放一次 |
| **统一错误提示** | 优先展示响应体的 `msg`，后端已给出可读原因 |
| **404 不提示"无权限"** | 见 0.4 的约定 |
| **请求追踪** | 生成 `X-Request-ID` 并回显在错误提示中，便于后端定位 |
| **分页组件** | 统一使用 `page`/`size`，读 `total`/`pages` |

## 6.4 联调注意事项

1. **验证码取不到**：开发环境验证码打印在**服务端日志**（`[Mock 验证码]` 开头），需向后端索取。
   接入真实短信后，发送接口会改为统一话术（不再区分手机号是否已注册）。
2. **时间格式**：后端返回 ISO-8601 本地时间（如 `2026-10-12T10:00:00`），**无时区后缀**，按本地时间解析。
3. **文件下载**：响应是二进制流，不是统一 JSON。前端用 `response.blob()` 处理。
4. **`balance` 是小数**：`DECIMAL(20,6)`，JSON 里可能是数字或字符串，建议按字符串处理再格式化。
5. **`PdfPagePreview` 返回图片流**，不是 JSON，需要 `blob` 处理。
6. **长文本字段**：`instruction`、`toolArgs`、`toolResult` 等可能很长，界面需做截断与展开。

---

# 七、当前实现边界（前端需知）

| 能力 | 状态 | 对前端的影响 |
| --- | --- | --- |
| AI 模型调用 | 同步回复与确认后端已接通，真实模型效果待验收 | 使用 replies 流程展示差异、确认/放弃；识别 stub，旧 messages 仅记录消息 |
| 任务进度推送 | **仅轮询** | 需自行实现轮询与停止策略 |
| OCR | 后端已接入 OpenAI 兼容云端模型，真实供应商和中文质量待验收 | 默认关闭；启用后发送扫描页，展示坐标、置信度和低置信度复核标记 |
| PDF 转可编辑 DOCX | 已有后端入口 | 原生文字直接转换；扫描页需显式传 `ocrLanguage` 并配置默认 OCR 模型 |
| 图片基础处理 | 已有后端接口 | 格式转换/缩放/裁剪/直角旋转/亮度/单行水印；图片模型不在范围 |
| 前端工程 | **不存在** | 已有技术与部署建议，工程位置和方案落地仍待确认 |
| 邮件验证码 | 未实现 | 只有短信渠道（当前为 Mock） |
