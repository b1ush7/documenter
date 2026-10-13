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
| 429 | 频率限制（验证码、上传次数、重试次数） |
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
| 任务类型 | `OCR` / `CONVERT` / `AI_EDIT` / `EXPORT` / `IMAGE_GEN` |
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

### 可选：注册验证码预校验

```
POST /user/verify/code
```

请求 `{ "phone": "13800000000", "code": "123456" }`

用于注册流程中「提交注册前先确认验证码有效」，避免用户填完表单才被告知验证码已过期。

> **注意：本接口会真正消费验证码**，成功后验证码立即失效。
> 因此调用它之后必须紧接着提交注册，不能只做校验不提交。
> 另外它固定使用 **REGISTER** 场景，不能用于校验登录或找回密码的验证码。

响应 `data`：`"****0000"`（脱敏手机号字符串）

错误：

| code | 含义 |
| --- | --- |
| 400 | 手机号格式不正确 / 该手机号尚未注册 |
| 403 | 账号已被禁用 |
| 429 | 验证码发送过于频繁（默认 60 秒间隔）/ 今日发送次数已达上限（默认 10 次） |
| 502 | 验证码发送失败（外部服务问题） |

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

### 8.2 文档结构读取（按格式）

```
GET /file/{fileId}/versions/{versionNo}/structure              DOCX 结构
GET /file/{fileId}/versions/{versionNo}/pdf/content            PDF 文本与页面类型
GET /file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview  单页渲染图
GET /file/{fileId}/versions/{versionNo}/xlsx/structure         XLSX 结构
GET /file/{fileId}/versions/{versionNo}/xlsx/range             XLSX 区域
GET /file/{fileId}/versions/{versionNo}/xlsx/formula-results   XLSX 公式计算结果
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
扫描页的 `text` 为空，需要后续走 OCR（P2 范围）。

**PDF 页面预览**返回**图片字节流**，不是 JSON。前端用 `URL.createObjectURL` 渲染。
页数有限制，超大 PDF 只会渲染部分页。

**XLSX 结构**包含 `sheets[]`（含 `firstRow`/`lastRow`）、`cells[]`、`styles[]`、`charts[]`。
单元格含 `address`、`type`、`value` 等；**读取单元格数量有上限**，超大表只返回部分。

**XLSX 公式结果**：`results[]` 每项含 `address`、`formula`、`status`、计算值。
后端用 POI 即时计算**支持的公式**；不支持的函数会返回明确的"需在办公软件中重算"提示，
**前端要把这个提示展示出来，不要显示成计算失败**。

### 8.3 DOCX 编辑

```
POST /file/{fileId}/versions/{versionNo}/paragraph-text       替换段落文本
POST /file/{fileId}/versions/{versionNo}/paragraph-edits      插入/删除段落
POST /file/{fileId}/versions/{versionNo}/table-edits          编辑表格
POST /file/{fileId}/versions/{versionNo}/paragraph-formats    设置标题/正文/列表格式
POST /file/{fileId}/versions/{versionNo}/inline-images        插入图片
```

统一请求结构（以段落替换为例）：

```json
{
  "instruction": "把第二段改得更正式",
  "operations": [
    { "blockId": "p-2", "text": "新的段落文本" }
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
| table-edits | `type`、`blockId`、`text` |
| paragraph-formats | `type`、`blockId`、`level`（标题层级） |
| inline-images | `blockId`、`imageFileId`、`imageVersionNo`、`widthPixels`、`heightPixels` |

**响应统一返回新建的版本 `Version`**，前端据此刷新版本列表与预览。

**约束**：部分操作会被拒绝（如"删除表格中唯一的行"、"替换含图片的段落"），
返回 `400` 或 `422`，`msg` 会说明原因。前端应把 `msg` 原样展示。

### 8.4 XLSX 编辑

```
POST /file/{fileId}/versions/{versionNo}/xlsx/cell-edits        单元格读写
POST /file/{fileId}/versions/{versionNo}/xlsx/structure-edits   行列增删
POST /file/{fileId}/versions/{versionNo}/xlsx/sheet-edits       工作表增删改名
POST /file/{fileId}/versions/{versionNo}/xlsx/data-edits        排序与筛选
POST /file/{fileId}/versions/{versionNo}/xlsx/formats           基础格式
```

请求公共字段：`instruction`、`sheetName`、`range`（区域，如 `A1:C10`）。

各接口特有字段：

| 接口 | 特有字段 |
| --- | --- |
| cell-edits | `cell` / `range`、`valueType`（`TEXT`/`NUMBER`/`FORMULA`）、`value`、`formula`、`recalculateFormulas` |
| structure-edits | `type`（插入/删除行或列）、`start`、`count` |
| sheet-edits | `type`（新增/重命名/删除）、`sheetName`、`newName`、`position` |
| data-edits | `type`（排序/筛选）、`range`、`keyColumn`、`direction`、`hasHeader` |
| formats | `range`、`fontName`、`fontSize`、`bold`、`italic`、`fontColor`、`horizontalAlignment`、`verticalAlignment`、`wrapText`、`fillColor`、`numberFormat`、`borderStyle` |

**注意 `valueType`**：区分文本与公式。如果把 `=SUM(A1:A2)` 当普通文本写入，会变成字符串而不是公式。
前端在"用户输入以 `=` 开头"时应自动切换为 `FORMULA`，或提供显式开关。

## P9 版本历史页

见 8.1 的接口。前端建议展示为时间线，每项显示：

- `versionNo` 与 `changeType` 的图标（上传/AI 编辑/人工编辑/转换/恢复）
- `instruction`（产生该版本的自然语言指令）
- `resultSummary`（结果说明）
- `createTime`、`sizeBytes`
- 操作：预览、下载、**恢复到此版本**

## P10 图片编辑

```
POST /file/{fileId}/versions/{versionNo}/image-edits
```

```json
{
  "instruction": "裁剪并旋转",
  "type": "CROP",
  "x": 0, "y": 0, "width": 800, "height": 600,
  "degrees": 90,
  "outputFormat": "PNG",
  "jpegQuality": 0.9
}
```

| 字段 | 说明 |
| --- | --- |
| type | 操作类型（裁剪/缩放/旋转/压缩/格式转换等） |
| x, y, width, height | 区域类操作的坐标与尺寸 |
| degrees | 旋转角度 |
| outputFormat | 输出格式；`PNG` 保留透明通道，`JPEG` 合成白色背景 |
| jpegQuality | JPEG 质量 |

响应：新建的版本 `Version`。

**前端注意**：坐标必须映射回**原图像素**。如果界面做了缩放显示，需要自行换算，否则裁剪位置会偏移。

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

**若接了模板**：`GET /admin/templates` 返回的 `enabled=true` 项里有 `variables`（JSON 数组字符串），
前端解析后动态渲染表单。变量定义形如：

```json
[
  { "name": "title", "label": "标题", "required": true, "description": "文档标题" }
]
```

`name` 是占位符标识（只允许字母数字下划线、不能以数字开头），`label` 用于界面显示。

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
重试会**新建一个任务**，原任务记录保留（便于排查失败原因）。
重试次数达上限返回 `429`。

### 进度更新方式

目前**只有轮询**：`GET /task/{taskId}`。

建议：任务处于 `PENDING`/`RUNNING` 时每 2 秒轮询一次，进入终态后停止。
任务列表页同理，但有列表时可放宽到 5 秒。

> 后端 TODO 3.4 里"是否用 SSE 推送进度"尚未定案。若后续改用 SSE，
> 会在现有服务上**新增**推送端点，不会破坏现有轮询接口。

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
| taskType | `OCR`/`CONVERT`/`AI_EDIT`/`EXPORT`/`IMAGE_GEN` |
| inputVersion | 不传表示使用当前最新版本 |
| idempotencyKey | **幂等键**：同一用户同一键只会创建一个任务，前端可安全重试提交 |
| expectVersion | 期望的当前版本，用于并发保护，不符返回 `409` |
| timeoutSeconds | 10-3600，不传取服务端默认 |

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

> ### 重要：当前不生成 AI 回复
>
> 模型调用属于 **TODO 4.2（P1 阶段）**，尚未接入。
> `POST /ai/sessions/{sessionId}/messages` **只落库用户消息**，不会返回助手回复。
>
> **前端不要显示"AI 正在思考"或 loading 态**，否则用户会一直等待。
> 建议在界面上明确标注"模型接入中"，或暂时隐藏发送入口。

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
```

响应 `data`：`DocTemplate`

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

**`variables` 是字符串形式的 JSON 数组**，前端需要二次 `JSON.parse`。
格式约束：`name` 必填、只允许字母数字下划线且不能以数字开头；非法结构后端返回 `400`。

**替换内容不删除旧文件**（便于回退），清理由后台任务负责。

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
| AI 模型调用 | **未接入**（P1） | 对话只落库用户消息，不要显示 AI 回复或 loading |
| 任务进度推送 | **仅轮询** | 需自行实现轮询与停止策略 |
| OCR | 未实现（P2） | 扫描型 PDF 的 `text` 为空 |
| PDF 转可编辑 DOCX | 未实现（P2） | 无此入口 |
| 图片模型编辑（去背景等） | 未实现（P2） | 只有本地确定性操作（裁剪/缩放/旋转/压缩/格式转换） |
| 前端工程 | **不存在** | 技术选型、构建与部署方式均待定 |
| 邮件验证码 | 未实现 | 只有短信渠道（当前为 Mock） |
