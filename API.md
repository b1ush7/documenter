# Documenter HTTP API 文档

本文档根据当前 `dev` 工作区中的 Controller、DTO、VO、安全配置和服务实现整理，描述当前已经存在的 HTTP 接口，不包含 TODO 中尚未实现的接口。

- 默认服务地址：`http://localhost:8080`
- 默认数据格式：`application/json; charset=UTF-8`
- 时间格式：ISO-8601 本地时间，例如 `2026-10-11T14:30:00`
- 最近更新日期：2026-10-17

## 1. 通用约定

### 1.1 认证

除下列公开接口外，其余接口都需要 Access Token：

- `POST /user/register`
- `POST /user/register/by-code`
- `POST /user/register/code`
- `POST /user/login`
- `POST /user/login/by-code`
- `POST /user/login/code`
- `POST /user/reset-password`
- `POST /user/reset-password/code`
- `POST /user/verify/code`
- `POST /user/refresh`

受保护接口通过请求头传递 Access Token：

```http
Authorization: Bearer <accessToken>
```

当前 Access Token 默认有效期为 15 分钟，Refresh Token 默认有效期为 7 天。刷新成功后旧 Refresh Token 立即失效。

### 1.2 请求追踪

客户端可以传入 8～64 位的 `X-Request-ID`，允许字符为字母、数字、下划线、点、冒号和连字符。未传或格式不正确时，服务端自动生成，并在响应头中返回：

```http
X-Request-ID: 1f82ac53ac0f45e8a95e9e8a429f0d31
```

### 1.3 统一 JSON 响应

除文件下载接口外，接口统一返回：

```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | `0` 表示成功，非 `0` 表示失败 |
| `msg` | string | 提示或错误信息；部分成功响应为空字符串 |
| `data` | any/null | 业务数据 |

业务异常和参数校验异常当前通常仍返回 HTTP `200`，调用方必须同时判断响应体中的 `code`。认证入口直接拒绝请求时会返回 HTTP `401`，权限不足时返回 HTTP `403`。

常见业务码：

| code | 含义 |
| --- | --- |
| `400` | 参数或业务条件不满足 |
| `401` | 未登录、Token 无效，或登录凭据错误 |
| `403` | 账号禁用或权限不足 |
| `404` | 文件、版本或任务不存在；无权访问他人资源时也按不存在处理 |
| `409` | 版本冲突或当前状态不允许操作 |
| `413` | 文件大小、页数、像素或解压规模超过限制 |
| `415` | 文件类型不支持 |
| `422` | PDF、图片、Office 压缩包或 DOCX 内容损坏，或结构暂不支持处理 |
| `429` | 发送、上传、重试次数或每日 AI 调用次数达到限制 |
| `500` | 服务端内部错误 |
| `502` | 验证码发送失败 |

参数校验失败示例：

```json
{
  "code": 400,
  "msg": "参数传递有误或者缺少参数",
  "data": null
}
```

### 1.4 分页响应

文件列表和任务列表返回统一分页结构：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 20,
  "pages": 0
}
```

### 1.5 接口总览

| 模块 | 方法 | 路径 | 认证 | 用途 |
| --- | --- | --- | --- | --- |
| 用户 | POST | `/user/register` | 否 | 密码注册 |
| 用户 | POST | `/user/register/by-code` | 否 | 验证码注册 |
| 用户 | POST | `/user/login` | 否 | 用户名或手机号密码登录 |
| 用户 | POST | `/user/login/by-code` | 否 | 手机验证码登录 |
| 用户 | POST | `/user/refresh` | 否 | 刷新双 Token |
| 用户 | GET | `/user/me` | 是 | 获取当前用户 |
| 用户 | POST | `/user/change-password` | 是 | 修改密码并撤销全部会话 |
| 用户 | POST | `/user/reset-password` | 否 | 使用手机验证码重置密码 |
| 用户 | POST | `/user/logout` | 是 | 退出当前设备 |
| 用户 | POST | `/user/logout-all` | 是 | 退出其他设备 |
| 验证码 | POST | `/user/register/code` | 否 | 发送注册验证码 |
| 验证码 | POST | `/user/login/code` | 否 | 发送登录验证码 |
| 验证码 | POST | `/user/reset-password/code` | 否 | 发送找回密码验证码 |
| 验证码 | POST | `/user/verify/code` | 否 | 消费并校验注册验证码 |
| 文件 | POST | `/file/upload` | 是 | 上传文件 |
| 文件 | GET | `/file/list` | 是 | 文件分页列表 |
| 文件 | GET | `/file/{fileId}` | 是 | 文件详情 |
| 文件 | PUT | `/file/{fileId}/name` | 是 | 文件重命名 |
| 文件 | DELETE | `/file/{fileId}` | 是 | 软删除文件 |
| 文件 | GET | `/file/{fileId}/download` | 是 | 下载当前最新版本内容 |
| DOCX | POST | `/file/docx/generate` | 是 | 按结构生成 DOCX |
| 版本 | GET | `/file/{fileId}/versions` | 是 | 版本列表 |
| 版本 | GET | `/file/{fileId}/versions/{versionNo}` | 是 | 版本详情 |
| 版本 | GET | `/file/{fileId}/versions/{versionNo}/download` | 是 | 下载指定版本 |
| 图片 | POST | `/file/{fileId}/versions/{versionNo}/image-edits` | 是 | 本地编辑 PNG/JPEG 并新建版本 |
| DOCX | GET | `/file/{fileId}/versions/{versionNo}/structure` | 是 | 读取 DOCX 结构 |
| PDF | GET | `/file/{fileId}/versions/{versionNo}/pdf/content` | 是 | 提取 PDF 原生文本并检测页面类型 |
| PDF | GET | `/file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview` | 是 | 渲染 PDF 指定页 PNG 预览 |
| XLSX | GET | `/file/{fileId}/versions/{versionNo}/xlsx/structure` | 是 | 读取工作表与单元格结构 |
| XLSX | GET | `/file/{fileId}/versions/{versionNo}/xlsx/range` | 是 | 读取指定矩形区域 |
| XLSX | GET | `/file/{fileId}/versions/{versionNo}/xlsx/formula-results` | 是 | 即时计算区域内公式，返回结果或错误 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/cell-edits` | 是 | 编辑单元格值或公式并新建版本 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/structure-edits` | 是 | 插入或删除行列并新建版本 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/sheet-edits` | 是 | 新增、重命名或删除工作表并新建版本 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/data-edits` | 是 | 排序区域或设置、清除自动筛选并新建版本 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/formats` | 是 | 设置单元格区域基础格式并新建版本 |
| XLSX | POST | `/file/{fileId}/versions/{versionNo}/xlsx/images` | 是 | 内嵌已有图片的明确版本并新建 XLSX 版本 |
| DOCX | POST | `/file/{fileId}/versions/{versionNo}/paragraph-text` | 是 | 替换段落文本 |
| DOCX | POST | `/file/{fileId}/versions/{versionNo}/paragraph-edits` | 是 | 插入或删除段落 |
| DOCX | POST | `/file/{fileId}/versions/{versionNo}/table-edits` | 是 | 编辑表格 |
| DOCX | POST | `/file/{fileId}/versions/{versionNo}/paragraph-formats` | 是 | 设置标题或列表格式 |
| DOCX | POST | `/file/{fileId}/versions/{versionNo}/inline-images` | 是 | 插入已有图片 |
| 版本 | POST | `/file/{fileId}/versions/{versionNo}/restore` | 是 | 恢复历史版本 |
| 任务 | POST | `/task` | 是 | 提交异步任务 |
| 任务 | GET | `/task/list` | 是 | 任务分页列表 |
| 任务 | GET | `/task/{taskId}` | 是 | 任务与步骤详情 |
| 任务 | POST | `/task/{taskId}/cancel` | 是 | 取消任务 |
| 任务 | POST | `/task/{taskId}/retry` | 是 | 重试任务 |
| 任务 | GET | `/task/by-file/{fileId}` | 是 | 查询文件处理历史 |
| AI 对话 | POST | `/ai/sessions/{sessionId}/replies` | 是 | 生成并保存回复及待确认计划 |
| AI 对话 | GET | `/ai/sessions/{sessionId}/replies/{messageId}` | 是 | 查询回复和确认前修改预览 |
| AI 对话 | POST | `/ai/sessions/{sessionId}/replies/{messageId}/confirm` | 是 | 确认计划并保存一个新版本 |
| AI 对话 | POST | `/ai/sessions/{sessionId}/replies/{messageId}/discard` | 是 | 放弃待确认计划 |
| 管理员 | GET | `/admin/users` | 管理员 | 用户分页列表 |
| 管理员 | GET | `/admin/users/{userId}` | 管理员 | 用户详情 |
| 管理员 | GET | `/admin/users/{userId}/usage` | 管理员 | 单用户资源用量与任务统计 |
| 管理员 | PUT | `/admin/users/{userId}/status` | 管理员 | 启用或禁用账号 |
| 管理员 | PUT | `/admin/users/{userId}/role` | 管理员 | 调整用户角色 |
| 管理员 | GET | `/admin/statistics/overview` | 管理员 | 系统使用量与任务状态概览 |
| 管理员 | GET | `/admin/files` | 管理员 | 跨用户文件分页列表 |
| 管理员 | GET | `/admin/files/{fileId}` | 管理员 | 任意用户文件详情 |
| 管理员 | PUT | `/admin/files/{fileId}/name` | 管理员 | 重命名任意用户文件 |
| 管理员 | DELETE | `/admin/files/{fileId}` | 管理员 | 软删除任意用户文件 |
| 管理员 | GET | `/admin/files/{fileId}/download` | 管理员 | 下载任意用户当前文件 |
| 管理员 | GET | `/admin/files/{fileId}/versions` | 管理员 | 任意用户文件版本列表 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}` | 管理员 | 任意用户文件版本详情 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/download` | 管理员 | 下载任意用户指定版本 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/structure` | 管理员 | 读取任意用户 DOCX 结构 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/pdf/content` | 管理员 | 读取任意用户 PDF 原生文本 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview` | 管理员 | 预览任意用户 PDF 页面 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/structure` | 管理员 | 读取任意用户 XLSX 结构 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/range` | 管理员 | 读取任意用户 XLSX 区域 |
| 管理员 | GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/formula-results` | 管理员 | 查看任意用户 XLSX 公式计算结果 |
| 管理员 | GET | `/admin/tasks` | 管理员 | 跨用户任务分页列表 |
| 管理员 | GET | `/admin/tasks/{taskId}` | 管理员 | 任意用户任务与步骤详情 |
| 管理员 | POST | `/admin/tasks/{taskId}/cancel` | 管理员 | 取消任意用户任务 |
| 管理员 | POST | `/admin/tasks/{taskId}/retry` | 管理员 | 重试任意用户任务 |

## 2. 用户与认证

### 2.1 密码注册

`POST /user/register`

请求体：

```json
{
  "username": "demo_user",
  "phone": "13800138000",
  "password": "password123"
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `username` | 是 | 4～20 位，只能包含字母、数字和下划线 |
| `phone` | 否 | 空字符串或中国大陆 11 位手机号 |
| `password` | 是 | 8～64 位 |

成功时 `data` 为 [UserInfo](#61-userinfo)。此接口只完成注册，不签发 Token。

### 2.2 发送注册验证码

`POST /user/register/code`

```json
{
  "phone": "13800138000"
}
```

手机号必须尚未注册。成功时 `data` 为脱敏手机号，例如：

```json
{
  "code": 0,
  "msg": "验证码已发送",
  "data": "138****8000"
}
```

开发环境目前使用模拟发送器，验证码写入服务端日志。发送间隔、有效期和每日次数由服务端配置控制。

发送成功后，摘要、错误计数与有效期在 Redis 中一次原子发布。若验证码已投递但无法确认保存成功，返回业务码 503，不能按发送成功处理；需等重发间隔后重新获取。此时不会生成成功发送审计。日发送尝试计数和既有重发间隔策略保持不变。

### 2.3 验证码注册

`POST /user/register/by-code`

```json
{
  "username": "demo_user",
  "phone": "13800138000",
  "code": "123456",
  "password": "password123"
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `username` | 是 | 4～20 位，只能包含字母、数字和下划线 |
| `phone` | 是 | 中国大陆 11 位手机号 |
| `code` | 是 | 4～8 位数字 |
| `password` | 是 | 8～64 位 |

验证码校验成功后会被消费，不能再次使用。成功时 `data` 为 [UserInfo](#61-userinfo)。

### 2.4 密码登录

`POST /user/login`

```json
{
  "account": "demo_user",
  "password": "password123"
}
```

`account` 可以是用户名或手机号。成功时 `data` 为 [TokenPair](#62-tokenpair)。账号不存在和密码错误统一返回 `账号或密码错误`。

### 2.5 发送登录验证码

`POST /user/login/code`

请求体与发送注册验证码相同。手机号必须已经注册，且账号未被禁用。

### 2.6 验证码登录

`POST /user/login/by-code`

```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

手机号必须为中国大陆 11 位手机号，验证码为 4～8 位数字。验证码成功使用后立即失效。成功时 `data` 为 [TokenPair](#62-tokenpair)。

### 2.7 发送找回密码验证码

`POST /user/reset-password/code`

请求体与发送注册验证码相同。手机号必须已经注册，且账号未被禁用。该验证码固定属于 `RESET_PASSWORD` 场景，不能用于注册或验证码登录。

### 2.8 找回并重置密码

`POST /user/reset-password`

```json
{
  "phone": "13800138000",
  "code": "123456",
  "newPassword": "newPassword123"
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `phone` | 是 | 中国大陆 11 位手机号 |
| `code` | 是 | 4～8 位数字，且必须属于找回密码场景 |
| `newPassword` | 是 | 8～64 位 |

成功后验证码立即失效，并撤销该用户的全部登录会话。用户需要使用新密码重新登录。

### 2.9 独立校验注册验证码

`POST /user/verify/code`

```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

成功响应：

```json
{
  "code": 0,
  "msg": "验证通过",
  "data": null
}
```

注意：此接口校验的是注册场景验证码，而且会真正消费验证码。调用成功后，同一验证码不能再提交给 `/user/register/by-code`。

所有验证码消费入口使用同一规则：错误次数未达上限返回业务码 400；在达到上限的那次错误尝试中立即删除验证码并返回 429。成功消费、错误次数用尽或过期后，再提交原验证码返回 400，需重新获取；注册、登录和找回密码三种用途隔离。并发原子性依赖 Redis Lua，专用 Redis 集成测试与运行方法见 [安全回归说明](docs/security-testing.md)。

### 2.10 刷新 Token

`POST /user/refresh`

```json
{
  "refreshToken": "<refresh-token>"
}
```

不要求携带仍有效的 Access Token。成功时返回一对新 Token，旧 Refresh Token 立即失效。

### 2.11 当前用户

`GET /user/me`

成功时 `data` 为 [UserInfo](#61-userinfo)。

### 2.12 修改密码

`POST /user/change-password`

```json
{
  "oldPassword": "password123",
  "newPassword": "newPassword123"
}
```

`oldPassword` 必填；`newPassword` 必填且长度为 8～64 位。成功后撤销包括当前设备在内的全部登录会话，调用方应清理本地 Token 并跳转登录页。

### 2.13 退出当前设备

`POST /user/logout`

请求体为空，需要携带当前 Access Token。服务端撤销当前 JWT 会话。

当前成功响应的 `msg` 为空，提示文字位于 `data`：

```json
{
  "code": 0,
  "msg": "",
  "data": "登出成功"
}
```

### 2.14 退出其他设备

`POST /user/logout-all`

请求体为空。保留当前 Access Token 对应的会话，撤销该用户的其他会话。`data` 为撤销的会话数量。

## 3. 文件与版本

### 3.1 上传文件

`POST /file/upload`

请求类型为 `multipart/form-data`，表单字段名固定为 `file`：

```bash
curl -X POST "http://localhost:8080/file/upload" \
  -H "Authorization: Bearer <accessToken>" \
  -F "file=@report.docx"
```

当前支持按实际内容识别的格式：

- DOCX
- XLSX
- PDF
- PNG
- JPEG
- GIF
- BMP

服务端不信任客户端提供的扩展名和 Content-Type。ZIP 类型会扫描完整条目列表，因此关键 OOXML 条目位于文件后部时仍能正确识别。

默认上传限制：

| 项目 | 默认值 | 配置项 |
| --- | --- | --- |
| 单文件大小 | 50 MB | `res.storage.upload.max-size-bytes` |
| 同一用户上传次数 | 每自然日 60 次 | `res.storage.upload.hourly-limit`（字段名为历史遗留，当前实现按自然日计数） |
| PDF 页数 | 200 页 | `res.storage.upload.max-pdf-pages` |
| 图片总像素 | 4000 万像素 | `res.storage.upload.max-image-pixels` |
| DOCX/XLSX ZIP 条目 | 10000 个 | `res.storage.upload.max-archive-entries` |
| DOCX/XLSX 解压总大小 | 200 MB | `res.storage.upload.max-archive-expanded-size-bytes` |
| DOCX/XLSX 压缩比 | 200 倍 | `res.storage.upload.max-archive-compression-ratio` |

PDF 只读取页数，图片只读取宽高元数据，DOCX/XLSX 采用流式扫描且不会解压到磁盘。上传成功后建立版本 1，`data` 为 [FileAsset](#63-fileasset)。隔离临时目录默认保留文件 24 小时，每小时执行一次清理。

### 3.2 文件列表

`GET /file/list`

查询参数：

| 参数 | 必填 | 默认值 | 约束/说明 |
| --- | --- | --- | --- |
| `page` | 否 | `1` | 大于等于 1 |
| `size` | 否 | `20` | 1～100 |
| `keyword` | 否 | - | 展示名模糊搜索，最长 100 字符 |
| `status` | 否 | `READY` | 常用值：`READY`、`DELETED`、`QUARANTINED` |

返回 `PageResult<FileAsset>`，按创建时间倒序。

### 3.3 文件详情

`GET /file/{fileId}`

成功时 `data` 为 [FileAsset](#63-fileasset)。只能访问当前用户自己的文件。

### 3.4 文件重命名

`PUT /file/{fileId}/name`

```json
{
  "displayName": "季度报告.docx"
}
```

`displayName` 必填，最长 200 字符。重命名只修改展示名，不移动底层存储文件。已删除文件不能重命名。

### 3.5 删除文件

`DELETE /file/{fileId}`

执行软删除，历史记录和磁盘内容保留。重复删除返回成功。

### 3.6 下载文件

`GET /file/{fileId}/download`

成功时直接返回二进制文件，不使用统一 JSON 包装，并设置：

```http
Content-Disposition: attachment; filename*=UTF-8''...
Content-Type: <文件实际 MIME 类型>
Content-Length: <字节数>
```

文件主记录在新增或恢复版本时同步为最新版本，下载的内容、类型、长度和扩展名对应当前最新版本。需要稳定获取某个明确版本时，应使用 `/file/{fileId}/versions/{versionNo}/download`。即使展示名经过重命名，下载名的后缀仍使用实际格式。

### 3.7 版本列表

`GET /file/{fileId}/versions`

返回 `Version[]`，按版本号倒序。版本号从 1 开始。

### 3.8 版本详情

`GET /file/{fileId}/versions/{versionNo}`

成功时 `data` 为 [Version](#64-version)。

### 3.9 下载指定版本

`GET /file/{fileId}/versions/{versionNo}/download`

直接返回二进制文件，使用指定版本独立保存的 MIME 类型、扩展名和大小，不沿用当前版本格式。下载文件名自动带版本后缀，例如 `report.docx` 的版本 2 下载为 `report-v2.docx`；当前图片已转为 JPEG 时，历史 PNG 仍下载为 `photo-v1.png`。

### 3.10 恢复历史版本

`POST /file/{fileId}/versions/{versionNo}/restore`

可选查询参数：

| 参数 | 说明 |
| --- | --- |
| `expectVersion` | 客户端认为的当前最新版本号，用于阻止并发覆盖 |

示例：

```http
POST /file/10/versions/2/restore?expectVersion=5
```

恢复不会覆盖或删除历史，而是复用目标版本的内容和格式，创建一个新的 `RESTORE` 版本，并同步当前文件的内容、类型和扩展名。例如从 JPEG 恢复历史 PNG 后，当前下载也恢复为 PNG。成功时 `data` 为新 [Version](#64-version)。若 `expectVersion` 与当前版本不一致，返回业务码 `409`。

### 3.11 提取 PDF 原生文本与页面类型

`GET /file/{fileId}/versions/{versionNo}/pdf/content`

服务端读取一个明确 PDF 版本，返回 `PdfContent`。页面类型为：

- `TEXT`：存在原生文字且未检测到内嵌图片；
- `SCANNED`：没有原生文字但存在内嵌图片，需要后续 OCR；
- `MIXED`：同一页同时存在原生文字和内嵌图片；
- `EMPTY`：没有检测到原生文字或内嵌图片。

这是基于 PDF 内容对象的启发式分类，不执行 OCR，也不保证能识别只使用矢量路径绘制的文字或扫描内容。默认每页最多返回 100,000 个 Unicode 字符，整份文档最多返回 2,000,000 个；超出时通过 `textTruncated` 明确标记，限制可由部署环境变量调整。

### 3.12 渲染 PDF 页面预览

`GET /file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview`

可选查询参数 `dpi`，默认 `144`，允许范围为 `72`～`200`。成功时直接返回 `image/png`，不使用统一 JSON 包装，并设置内联文件名、`Content-Length`、`X-Image-Width` 和 `X-Image-Height`。

渲染前会按页面裁剪框和 DPI 估算像素数，并复用上传图片像素上限；超过限制返回业务码 `413`。页码从 1 开始，越界返回业务码 `404`。默认 DPI、最大 DPI 和像素上限均可通过部署配置调整。

### 3.13 读取 XLSX 结构

`GET /file/{fileId}/versions/{versionNo}/xlsx/structure`

响应示例：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "fileId": 10,
    "versionNo": 2,
    "totalSheets": 1,
    "includedCells": 3,
    "truncated": false,
    "sheets": [
      {
        "index": 0,
        "name": "数据",
        "firstRow": 0,
        "lastRow": 1,
        "mergedRegions": ["A2:B2"],
        "charts": [
          {"index": 0, "title": "销售额", "titleFormula": null, "types": ["BAR"]}
        ],
        "cells": [
          {
            "address": "A1",
            "rowIndex": 0,
            "columnIndex": 0,
            "type": "STRING",
            "cachedValueType": null,
            "value": "项目",
            "formula": null,
            "style": {
              "styleIndex": 1,
              "dataFormat": 0,
              "dataFormatString": "General",
              "horizontalAlignment": "CENTER",
              "verticalAlignment": "BOTTOM",
              "wrapText": true,
              "locked": true,
              "hidden": false,
              "fillPattern": "NO_FILL",
              "fillForegroundColor": 64,
              "borderTop": "NONE",
              "borderRight": "NONE",
              "borderBottom": "NONE",
              "borderLeft": "NONE",
              "fontName": "Calibri",
              "fontSize": 11.0,
              "bold": true,
              "italic": false
            }
          }
        ]
      }
    ]
  }
}
```

- 只返回实际存在的单元格；空工作表的 `firstRow`、`lastRow` 为 `null`。
- `address` 使用 A1 格式，行列下标从 0 开始。公式单元格的 `type` 为 `FORMULA`，`formula` 不含开头等号，`value` 和 `cachedValueType` 来自文件中已有的缓存结果；接口不会执行公式。
- `mergedRegions` 使用 A1 区域格式；图表以工作表内的 `index` 定位，并返回可读取的标题和图表类型。
- 单次默认最多返回 100000 个单元格，由 `res.storage.xlsx.max-read-cells` 控制；超出时 `truncated=true`。

读取一个明确的矩形区域：

```http
GET /file/10/versions/2/xlsx/range?sheetName=数据&range=A1:C20
```

返回 `XlsxRange`，其中 `rows` 是固定行列数的二维数组。与整表结构接口只返回实际存在的单元格不同，区域接口会为未创建的位置返回 `type=BLANK`、`value=""`、`style=null` 的占位单元格，便于前端形成稳定网格。区域必须使用 `A1:C20` 格式，包含的单元格数不能超过 `res.storage.xlsx.max-read-cells`。

### 3.14 编辑 XLSX 单元格

`POST /file/{fileId}/versions/{versionNo}/xlsx/cell-edits`

```json
{
  "instruction": "更新数量并计算合计",
  "operations": [
    {
      "type": "SET_VALUE",
      "sheetName": "数据",
      "cell": "B2",
      "valueType": "NUMBER",
      "value": "12.5"
    },
    {
      "type": "SET_FORMULA",
      "sheetName": "数据",
      "cell": "C2",
      "formula": "B2*2"
    },
    {
      "type": "CLEAR",
      "sheetName": "数据",
      "cell": "D2"
    }
  ]
}
```

| 操作 | 必须字段 | 说明 |
| --- | --- | --- |
| `SET_VALUE` | `sheetName`、`cell`、`valueType`、`value` | `valueType` 支持 `STRING`、`NUMBER`、`BOOLEAN`、`DATE` |
| `SET_FORMULA` | `sheetName`、`cell`、`formula` | 公式可带或不带开头等号，最长 8192 个字符 |
| `CLEAR` | `sheetName`、`cell` | 清空内容但保留单元格样式 |
| `SET_RANGE` | `sheetName`、`range`、`values` | 按二维矩阵批量写入，矩阵尺寸必须与区域完全一致 |
| `FILL_FORMULA` | `sheetName`、`range`、`formula` | 以区域左上角的公式为模板，向整个矩形区域填充 |

- `instruction` 可选，最长 2000；`operations` 必填，默认最多 500 项，由 `res.storage.xlsx.max-edit-operations` 控制。
- `cell` 必须是 XLSX 范围内的 A1 地址；同一请求不能重复修改同一目标。合并区域只能修改左上角，数组公式单元格暂不支持修改。
- `STRING` 即使以 `=` 开头也只写为文本。`DATE` 接受 `yyyy-MM-dd` 或 ISO 本地日期时间；原单元格没有日期格式时自动补充基础日期格式。
- 新公式不允许外部工作簿引用、URL、DDE 管道，或 `WEBSERVICE`、`HYPERLINK`、`RTD`、`DDE`、`CALL`、`REGISTER.ID` 等外部调用函数。默认只校验并写入公式；可通过 `recalculateFormulas=true` 同步重算并保存缓存结果，文件仍标记为在办公软件中打开时重算。
- 写入或填充公式时检查语法、直接引用的工作表是否存在、引用是否位于 XLSX 范围内，并拒绝 `#REF!`。对 `INDIRECT` 等动态引用的实际目标和输入类型，需通过计算结果检查，写入校验不能提前保证结果正确。
- 成功后以路径中的 `versionNo` 作为并发期望版本，创建新的 `MANUAL_EDIT` 版本，原版本不变；返回新 [Version](#64-version)。
`SET_RANGE` 中每个值的结构为 `{"valueType":"STRING","value":"示例"}`。`valueType` 支持 `STRING`、`NUMBER`、`BOOLEAN`、`DATE`、`FORMULA` 和 `BLANK`；`BLANK` 必须不传 `value`。单次请求按实际影响的单元格数计入 500 个默认上限，同一目标不能同时出现在单元格操作和区域操作中。

`FILL_FORMULA` 示例：

```json
{
  "instruction": "计算各行金额",
  "operations": [
    {"type": "FILL_FORMULA", "sheetName": "数据", "range": "D2:D20", "formula": "B2*C2*$F$1"}
  ]
}
```

模板代表左上角 `D2` 的公式，`D3` 得到 `B3*C3*$F$1`。二维填充同时调整行和列，相对引用随位置移动，绝对引用保持不变，混合引用只调整相对的部分。工作表名称包含空格时沿用 Excel 的单引号规则，例如 `'年度 数据'!B2`。填充按实际目标数计入默认 500 个上限；生成越界引用、重复目标或命中不能编辑的位置时整个请求失败，不保存部分结果。目标既有单元格样式保留。

需要让下载文件包含当前计算结果时，在请求顶层启用 `recalculateFormulas`：

```json
{
  "instruction": "修改数量并更新全部公式结果",
  "recalculateFormulas": true,
  "operations": [
    {"type": "SET_VALUE", "sheetName": "数据", "cell": "B2", "valueType": "NUMBER", "value": "12"}
  ]
}
```

- `recalculateFormulas` 是可选布尔值，默认 `false`；仅适用于本单元格编辑接口。
- 启用后，在所有编辑完成后重算**整个工作簿**，包括未直接编辑的公式和跨工作表依赖，再将公式和缓存结果一起保存进新版本。保留单元格样式；结果说明包含“已重算公式缓存”。
- 公式计算得到 `#DIV/0!`、`#VALUE!` 等 Excel 错误时，仍保存公式及错误缓存，可通过结构读取或公式结果接口查看；不会把错误值当作数字或文本。
- 引擎不支持某公式或依赖、或者计算失败时，返回业务码 `422`，说明失败位置并提示可关闭该选项后在办公软件中重算；整次编辑不保存，不会生成只重算一部分的版本。
- 重算沿用 3.19 的工作簿数量及安全限制：默认最多 500 个公式、100000 个已创建单元格。超限返回 `413`，外部引用或高风险函数返回 `400`，1904 日期系统返回 `422`。检查基于本次编辑后的整个工作簿。
- 未启用时保持现有行为，缓存可能仍是旧值；源版本始终保留。重算成功后仍标记为打开时重算，以便办公软件刷新易变函数等结果。

当前接口仍不支持图表编辑。

### 3.15 插入或删除 XLSX 行列

`POST /file/{fileId}/versions/{versionNo}/xlsx/structure-edits`

```json
{
  "instruction": "在表头后增加两行，并删除第三列",
  "operations": [
    {"type": "INSERT_ROWS", "sheetName": "数据", "start": 2, "count": 2},
    {"type": "DELETE_COLUMNS", "sheetName": "数据", "start": 3, "count": 1}
  ]
}
```

- `type` 支持 `INSERT_ROWS`、`DELETE_ROWS`、`INSERT_COLUMNS`、`DELETE_COLUMNS`。
- `start` 从 1 开始：行操作表示 Excel 行号，列操作中 `1` 表示 A 列；单个 `count` 为 1～1000，单次请求中的 `count` 总和默认不能超过 1000，可通过 `res.storage.xlsx.max-structural-units` 调整。多个操作按数组顺序执行，后续操作的位置基于前面操作完成后的工作表。
- 插入行会继承相邻行的高度、行样式和已有单元格样式；插入列继承相邻列的宽度、隐藏状态和默认样式。未修改的内容和样式随行列移动。
- Apache POI 会调整其支持的公式引用，工作簿标记为打开时重算。删除被公式引用的行列可能产生 `#REF!`，接口不会在服务端执行公式。
- 操作不能超出已有数据末尾，也不能切开合并区域；完整删除覆盖的合并区域会一并删除。受保护工作表、待移动区域中的数组公式，以及包含正式 Excel 表对象的工作表暂不支持行列增删。
- 成功后创建新的 `MANUAL_EDIT` 版本，源版本保持不变。

### 3.16 新增、重命名或删除 XLSX 工作表

`POST /file/{fileId}/versions/{versionNo}/xlsx/sheet-edits`

```json
{
  "instruction": "整理工作表",
  "operations": [
    {"type": "CREATE", "sheetName": "汇总", "position": 1},
    {"type": "RENAME", "sheetName": "Sheet1", "newName": "原始数据"},
    {"type": "DELETE", "sheetName": "临时数据"}
  ]
}
```

- `CREATE` 使用 `sheetName` 作为新名称；`position` 可选且从 1 开始，不传时追加到末尾。
- `RENAME` 必须提供现有 `sheetName` 和 `newName`；`DELETE` 只提供现有 `sheetName`。工作表名称必须符合 Excel 规则且不能重复。
- 多个操作按数组顺序执行，后续操作看到前面操作产生的结果。工作簿至少保留一个工作表；删除仍被公式引用的工作表可能使公式出现 `#REF!`。
- 单次最多 50 个操作。成功后创建新的 `MANUAL_EDIT` 版本，源版本保持不变。

### 3.17 XLSX 区域排序与自动筛选

`POST /file/{fileId}/versions/{versionNo}/xlsx/data-edits`

```json
{
  "instruction": "按金额从高到低排列并启用筛选",
  "operations": [
    {
      "type": "SORT_RANGE",
      "sheetName": "数据",
      "range": "A1:D100",
      "keyColumn": "D",
      "direction": "DESC",
      "hasHeader": true
    },
    {
      "type": "SET_AUTO_FILTER",
      "sheetName": "数据",
      "range": "A1:D100"
    }
  ]
}
```

| 操作 | 必须字段 | 说明 |
| --- | --- | --- |
| `SORT_RANGE` | `sheetName`、`range`、`keyColumn`、`direction`、`hasHeader` | 按区域内的绝对列排序；`direction` 为 `ASC` 或 `DESC` |
| `SET_AUTO_FILTER` | `sheetName`、`range` | 设置 Excel 自动筛选下拉区域，不在服务端执行筛选条件 |
| `CLEAR_AUTO_FILTER` | `sheetName` | 清除该工作表已有的自动筛选定义 |

- 排序会整体移动区域内每一行的值和单元格样式，保持相同键值的原始顺序，空白键始终排在末尾；`hasHeader=true` 时首行不参与排序。
- 为避免破坏复杂工作簿，排序区域不得与合并区域相交，也不能含公式、数组公式、批注或超链接；包含正式 Excel 表对象或受保护的工作表不支持这些数据操作。
- 单个排序区域默认最多 10000 个单元格，由 `res.storage.xlsx.max-sortable-cells` 控制；单次最多 20 个数据操作。
- 成功后创建新的 `MANUAL_EDIT` 版本，源版本保持不变。

### 3.18 设置 XLSX 基础格式

`POST /file/{fileId}/versions/{versionNo}/xlsx/formats`

```json
{
  "instruction": "设置表头格式",
  "operations": [
    {
      "sheetName": "数据",
      "range": "A1:D1",
      "fontName": "微软雅黑",
      "fontSize": 12,
      "bold": true,
      "fontColor": "#FFFFFF",
      "horizontalAlignment": "CENTER",
      "verticalAlignment": "CENTER",
      "wrapText": true,
      "fillColor": "4472C4",
      "numberFormat": "General",
      "borderStyle": "THIN"
    }
  ]
}
```

- `range` 支持单个地址 `A1` 或矩形区域 `A1:D10`。每个操作至少提供一个格式字段；未提供的格式沿用单元格原样，单元格值不变。
- 支持字体名称、字号（1～409）、粗体、斜体、字体 RGB 色，水平/垂直对齐、自动换行、填充 RGB 色、数字格式，以及四边相同的基础边框。
- `horizontalAlignment` 支持 `GENERAL`、`LEFT`、`CENTER`、`RIGHT`、`FILL`、`JUSTIFY`、`CENTER_SELECTION`、`DISTRIBUTED`；`verticalAlignment` 支持 `TOP`、`CENTER`、`BOTTOM`、`JUSTIFY`、`DISTRIBUTED`。
- `borderStyle` 支持 `NONE`、`THIN`、`MEDIUM`、`DASHED`、`DOTTED`、`THICK`、`DOUBLE`。颜色接受带或不带 `#` 的 6 位 RGB。
- 单次最多 100 个格式操作，实际影响单元格总数默认不能超过 500；格式区域不能只覆盖合并区域的一部分，受保护工作表不支持格式编辑。
- 成功后创建新的 `MANUAL_EDIT` 版本，源版本保持不变。

### 3.19 即时计算 XLSX 公式

`GET /file/{fileId}/versions/{versionNo}/xlsx/formula-results?sheetName=数据&range=D2:D20`

查询参数 `sheetName`、`range` 必填，`range` 接受 `D2` 或 `D2:D20`。返回区域内实际存在的公式单元格；普通值和空白位置不返回。计算基于指定版本中的实际数据，不使用文件中可能过期的公式缓存，不创建版本，也不更新原文件缓存。

响应示例：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "fileId": 10,
    "versionNo": 2,
    "sheetName": "数据",
    "range": "D2:D3",
    "results": [
      {"address": "D2", "formula": "B2*C2", "status": "OK", "valueType": "NUMERIC", "value": "120", "message": null},
      {"address": "D3", "formula": "1/0", "status": "ERROR", "valueType": "ERROR", "value": "#DIV/0!", "message": null}
    ]
  }
}
```

- `OK`：得到实际结果，`valueType` 为 `NUMERIC`、`STRING`、`BOOLEAN` 等；`value` 统一为字符串。具有日期格式的数值结果按 ISO 本地日期时间返回。
- `ERROR`：公式已执行但得到 Excel 错误，例如 `#DIV/0!`、`#VALUE!`、`#N/A`、`#REF!`。这些是单元格结果，外层响应仍成功。
- `UNSUPPORTED`：POI 明确报告函数或依赖尚未支持；`valueType`、`value` 为 `null`，`message` 提示在办公软件中重算。
- `EVALUATION_FAILED`：该公式或依赖计算失败；同样返回空结果和提示，不冒用旧缓存。动态数组等能力以实际计算引擎支持情况为准。
- 默认计算区域最多 500 个位置，整个工作簿最多 500 个公式、100000 个已创建单元格，分别沿用 `res.storage.xlsx.max-edit-operations` 和 `max-read-cells`；超出限制返回业务码 `413`。这些限制控制数量，不保证任意复杂公式的固定执行耗时。
- 工作簿或命名公式含外部引用、高风险函数时返回业务码 `400`；当前计算引擎不支持 1904 日期系统，返回业务码 `422` 并提示在办公软件中重算。
- 本接口仍是只读计算。单元格编辑可通过 `recalculateFormulas=true` 将计算结果写回新版本缓存，其他 XLSX 编辑接口仍只标记为打开时重算；实际办公软件兼容性验收尚未完成。

### 3.20 本地编辑 PNG/JPEG 图片

`POST /file/{fileId}/versions/{versionNo}/image-edits`

只操作当前用户拥有的明确图片版本，使用本地 Java2D/ImageIO，不调用图片模型或云服务。请求示例：

```json
{
  "instruction": "裁剪后缩放、提亮并添加标题，转为 JPEG",
  "operations": [
    {"type": "CROP", "x": 10, "y": 20, "width": 600, "height": 400},
    {"type": "RESIZE", "width": 300, "height": 200},
    {"type": "ROTATE", "degrees": 90},
    {"type": "BRIGHTNESS", "factor": 1.2},
    {"type": "ADD_TEXT", "text": "项目周报", "x": 10, "y": 10, "fontSize": 24, "color": "#112233"}
  ],
  "outputFormat": "JPEG",
  "jpegQuality": 0.85
}
```

| 字段 | 说明 |
| --- | --- |
| `instruction` | 可选，最长 2000 字符，仅记录操作说明，不会解析为 AI 指令 |
| `operations` | 最多 20 个操作，默认空数组，不能为 `null`；按顺序执行 |
| `outputFormat` | 可选，`PNG` 或 `JPEG`，不填沿用源版本格式 |
| `jpegQuality` | 可选，0.1～1，仅允许 JPEG 输出，默认 0.9；数值越低通常体积越小，但不保证比源文件小 |

操作字段必须与操作类型相符，不接受其他类型的字段：

- `CROP`：必填 `x`、`y`、`width`、`height`。坐标从左上角 `(0,0)` 开始，以像素计；起点不得为负，宽高必须大于 0，范围不能超出当前图片。
- `RESIZE`：必填正整数 `width`、`height`，按这两个尺寸缩放，使用双线性插值；不会自动保持宽高比，调用方应自行计算等比尺寸。
- `ROTATE`：只填 `degrees`，支持顺时针 `90`、`180`、`270` 度。90/270 度会交换宽高。
- `BRIGHTNESS`：只填有限数值 `factor`，范围 0～4。各 RGB 通道乘以该系数、四舍五入并截断到 255；0 为全黑、1 保持原值、大于 1 提亮。不改变 alpha，不执行自动曝光或伽马校正。
- `ADD_TEXT`：必填 `text`、非负整数 `x/y`，可选 `fontSize`（1～512 像素，默认 24）、`color`（`#RRGGBB`，默认 `#000000`）。单行非空纯文本，最多 500 个 UTF-16 字符，不允许换行、制表符或控制字符；不解析 HTML，不接受字体文件或字体路径。使用系统逻辑 SansSerif 普通字体，中文等字符依赖部署环境安装的字体；缺字返回 `422` 并说明应安装对应字体。

文字 `x/y` 是实际可见文字像素边界的左上角，不是基线或字框左上角；整个可见文字必须落在当前图片内，越界返回 `400`，不静默裁掉文字。原有像素保持不变，文字按抗锯齿方式覆盖合成。文字排版随操作系统、JDK 和字体而变化，不保证不同部署环境像素一致。

每个操作的坐标与尺寸以此前操作完成后的图片为准。至少提供一个操作、`outputFormat` 或 `jpegQuality`；仅转换格式或调整 JPEG 质量时可省略 `operations`。

- 源版本仅支持 PNG/JPEG，解码前校验实际格式和尺寸。源图片与每个中间结果默认最多 4000 万像素（`res.storage.upload.max-image-pixels`），源文件及编码后输出默认最多 50 MiB（`max-size-bytes`）；超限返回 `413`。
- PNG 输出保留透明通道；JPEG 不支持透明，透明区域合成到白色背景。转换为 JPEG 后再转回 PNG 不会恢复丢失的透明度或有损细节。
- 输出重新编码，不保留 EXIF、ICC、DPI 等元数据，也不按 EXIF 自动纠正方向；坐标基于实际解码像素。尚不支持任意角度旋转、多行或富文本排版、指定字体及模型编辑。
- 成功时返回新 [Version](#64-version)，包含 `contentType`、`extension`；跨格式为 `CONVERT`，同格式为 `MANUAL_EDIT`。历史版本保留原内容和原格式，当前文件同步到新版本。
- `versionNo` 同时为源版本和乐观锁期望值；不是最新版本时返回 `409`，不会覆盖最新内容，并清理已经生成的未入库产物。无权访问按 `404` 处理，字段、裁剪范围或文字范围无效返回 `400`，损坏、格式不符或缺字返回 `422`。所有操作完成后才写入单个新版本，任一操作失败都不保存部分结果。

已有数据库需要执行 Flyway `V2__version_content_types.sql`：为历史版本回填格式，并把当前文件内容指针同步到最新版本。该迁移随应用启动执行，真实 MariaDB 环境迁移验收仍需完成。

### 3.21 将已有图片插入 XLSX

`POST /file/{fileId}/versions/{versionNo}/xlsx/images`

需要登录。服务端按当前用户校验目标工作簿和每张源图片，图片必须为已上传或编辑后的 PNG/JPEG 明确版本；不读取外部 URL 或本地路径。本接口直接执行人工插图，不接入 AI 待确认计划。

```json
{
  "instruction": "在数据表右侧添加说明图片",
  "operations": [
    {"sheetName": "数据", "cell": "F2", "imageFileId": 30, "imageVersionNo": 2,
     "widthPixels": 400, "heightPixels": 300}
  ]
}
```

| 字段 | 规则 |
| --- | --- |
| `instruction` | 可选，最多 2000 字符，仅记录操作说明 |
| `operations` | 必填，1～20 个非空操作，按顺序插入；成功仅保存一个 MANUAL_EDIT 版本 |
| `sheetName` | 必填，已有工作表的准确名称；不存在返回 404，受保护工作表返回 422 |
| `cell` | 必填，锚点单元格，如 F2；支持大小写及绝对引用标记，必须在 A1～XFD1048576 内，不接受区域或带工作表名的地址 |
| `imageFileId` / `imageVersionNo` | 必填正数，图片文件 ID 和明确版本；图片已有新版本时也不会改用新版本 |
| `widthPixels` / `heightPixels` | 必填，各为 1～10000 像素，不自动保持宽高比 |

图片是浮动绘图对象，左上角对齐锚点单元格，不写入或覆盖该单元格值，也不会自动调整行高或列宽。可以在未创建的空白单元格位置插图，也允许显式重叠多张图片。图片采用随单元格移动、固定尺寸的 OOXML 锚定方式；计算显示范围时跳过隐藏行列，范围超出工作表边界返回 400。办公软件中后续插入行列时的兼容表现仍需人工验收。

服务端验证图片实际 PNG/JPEG 格式、可解码性、原始像素数及读取字节数，保留原始图片字节并内嵌到 XLSX。每张图片沿用上传像素上限（默认 4000 万），本批新增图片总字节及最终 XLSX 沿用文件大小上限（默认 50 MiB），超限返回 413；损坏或 MIME 与内容不一致返回 422，不支持的图片 MIME 返回 400。PNG 透明度随原始字节保留，不重新编码。

源图片仍为独立文件资源；内嵌副本的替代文本记录 `Documenter imageFileId=30; imageVersionNo=2`，用于保留来源。插入后不依赖外部图片链接，源图片后续更新不会改变既有 XLSX 内容。本次不扩展 XLSX 结构接口中的图片清单；保存后的页面显示、下载和恢复复用已有按版本接口。

整批在内存副本完成后才保存；任一图片或目标校验失败，不生成部分版本。目标与图片无权访问按 404 处理；源版本同时作为乐观锁期望值，版本冲突返回 409 并清理未入库产物。成功返回新 [Version](#64-version)。本次不增加数据库迁移，POI 重开测试已覆盖内容保留与边界，真实办公软件版式仍待验收。

## 4. DOCX 生成与编辑

### 4.1 通用版本规则

- 结构读取和编辑只支持 DOCX。
- 所有编辑接口都会创建新版本，不会原地覆盖源版本。
- 路径中的 `versionNo` 同时是源版本和乐观锁期望值；源版本不是当前最新版本时会返回 `409`。
- `blockId` 只在一个明确版本内稳定。创建新版本后必须重新读取结构，不能继续沿用旧版本的 `blockId`。
- DOCX 编辑产生的版本 `changeType` 为 `MANUAL_EDIT`。

### 4.2 按结构生成 DOCX

`POST /file/docx/generate`

完整请求示例：

```json
{
  "fileName": "项目周报.docx",
  "title": "项目周报",
  "instruction": "按周报结构生成初稿",
  "blocks": [
    {
      "type": "HEADING",
      "text": "本周进展",
      "level": 1
    },
    {
      "type": "PARAGRAPH",
      "text": "完成了 DOCX 结构化编辑功能。"
    },
    {
      "type": "BULLET_LIST",
      "items": ["段落编辑", "表格编辑", "图片插入"]
    },
    {
      "type": "TABLE",
      "rows": [
        ["事项", "状态"],
        ["接口文档", "完成"]
      ]
    },
    {
      "type": "IMAGE",
      "imageFileId": 21,
      "imageVersionNo": 1,
      "widthPixels": 640,
      "heightPixels": 360
    }
  ]
}
```

顶层字段：

| 字段 | 必填 | 约束/说明 |
| --- | --- | --- |
| `fileName` | 否 | 最长 200；未带 `.docx` 时自动补全，未传时使用 `未命名文件.docx` |
| `title` | 条件必填 | 最长 1000；`title` 与 `blocks` 不能同时为空 |
| `instruction` | 否 | 最长 2000，写入版本记录 |
| `blocks` | 条件必填 | 最多 200 个；`title` 与 `blocks` 不能同时为空 |

结构块：

| `type` | 必须字段 | 说明 |
| --- | --- | --- |
| `HEADING` | `text`、`level` | `text` 不能为空白；`level` 为 1～9 |
| `PARAGRAPH` | `text` | `text` 可以是空字符串，但不能为 `null` |
| `BULLET_LIST` | `items` | 项目符号列表，最多 100 项 |
| `NUMBERED_LIST` | `items` | 编号列表，最多 100 项 |
| `TABLE` | `rows` | 最多 100 行、每行 1～20 列；每行列数必须一致 |
| `IMAGE` | `imageFileId`、`imageVersionNo`、`widthPixels`、`heightPixels` | 图片必须属于当前用户，只支持 PNG/JPEG；宽高为 1～10000 像素 |

每个块只能携带与自身类型匹配的字段。成功后保存为一个 `source=GENERATED` 的新文件，并建立版本 1；`data` 为 [FileAsset](#63-fileasset)。

当前生成器使用固定的 A4 页面、页边距、宋体、字号和基础表格样式。接口控制内容和块顺序，不接受自定义模板、任意 OOXML 或任意本地路径。

### 4.3 读取 DOCX 结构

`GET /file/{fileId}/versions/{versionNo}/structure`

响应示例：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "fileId": 10,
    "versionNo": 3,
    "blocks": [
      {
        "blockId": "body/p0",
        "type": "HEADING",
        "text": "项目周报",
        "styleId": "Heading1",
        "headingLevel": 1,
        "name": null,
        "contentType": null,
        "sizeBytes": null,
        "children": []
      },
      {
        "blockId": "body/t0",
        "type": "TABLE",
        "text": null,
        "styleId": null,
        "headingLevel": null,
        "name": null,
        "contentType": null,
        "sizeBytes": null,
        "children": []
      }
    ]
  }
}
```

块类型为 `PARAGRAPH`、`HEADING`、`TABLE`、`ROW`、`CELL` 或 `IMAGE`。常见块路径：

| 块 | 示例 |
| --- | --- |
| 正文段落 | `body/p0` |
| 正文表格 | `body/t0` |
| 表格行 | `body/t0/r0` |
| 单元格 | `body/t0/r0/c1` |
| 单元格内段落 | `body/t0/r0/c1/p0` |
| 段落内图片 | `body/p0/img0` |

表格、行、单元格和段落通过 `children` 递归嵌套。

### 4.4 替换段落文本

`POST /file/{fileId}/versions/{versionNo}/paragraph-text`

```json
{
  "instruction": "修正文案",
  "operations": [
    {
      "blockId": "body/p2",
      "text": "替换后的完整段落文本"
    }
  ]
}
```

- `instruction` 可选，最长 2000。
- `operations` 必填，1～100 项。
- `blockId` 必须指向正文或表格单元格中的段落。
- `text` 必填，最长 100000；允许空字符串以清空段落。
- 同一段落不能在同一请求中重复替换。
- 包含图片或复杂内容的段落暂不支持整体文本替换。

成功时 `data` 为新 [Version](#64-version)。

### 4.5 插入或删除段落

`POST /file/{fileId}/versions/{versionNo}/paragraph-edits`

```json
{
  "instruction": "补充说明并删除旧段落",
  "operations": [
    {
      "type": "INSERT_AFTER",
      "blockId": "body/p1",
      "text": "新增段落"
    },
    {
      "type": "DELETE",
      "blockId": "body/p3"
    }
  ]
}
```

| 操作 | `blockId` | `text` |
| --- | --- | --- |
| `INSERT_BEFORE` | 段落 | 必填 |
| `INSERT_AFTER` | 段落 | 必填 |
| `DELETE` | 段落 | 必须不传 |

单次 1～100 项。同一段落不能同时作为插入参照和删除目标；表格单元格必须保留至少一个段落。

### 4.6 编辑表格

`POST /file/{fileId}/versions/{versionNo}/table-edits`

```json
{
  "instruction": "更新表格",
  "operations": [
    {
      "type": "SET_CELL_TEXT",
      "blockId": "body/t0/r1/c1",
      "text": "已完成"
    },
    {
      "type": "INSERT_ROW_AFTER",
      "blockId": "body/t0/r1",
      "values": ["接口联调", "进行中"]
    }
  ]
}
```

| 操作 | 目标 | 附加字段 |
| --- | --- | --- |
| `SET_CELL_TEXT` | `CELL` | `text` 必填；允许空字符串，不得传 `values` |
| `INSERT_ROW_BEFORE` | `ROW` | `values` 必填，列数必须与参照行一致 |
| `INSERT_ROW_AFTER` | `ROW` | `values` 必填，列数必须与参照行一致 |
| `DELETE_ROW` | `ROW` | 不得传 `text` 或 `values` |

单次 1～100 项；每个插入行最多 100 个单元格，每个文本最长 100000。表格必须保留至少一行，包含嵌套表格的单元格暂不支持整体替换。

### 4.7 设置标题、正文或列表格式

`POST /file/{fileId}/versions/{versionNo}/paragraph-formats`

```json
{
  "instruction": "调整层级和列表样式",
  "operations": [
    {
      "type": "SET_HEADING",
      "blockId": "body/p0",
      "level": 1
    },
    {
      "type": "SET_BULLET",
      "blockId": "body/p2"
    }
  ]
}
```

| 操作 | `level` |
| --- | --- |
| `SET_HEADING` | 必填，1～9 |
| `SET_NORMAL` | 必须不传 |
| `SET_BULLET` | 必须不传 |
| `SET_NUMBERED` | 必须不传 |
| `CLEAR_LIST` | 必须不传 |

单次 1～100 项，同一段落在一次请求中只能设置一种格式。

### 4.8 插入图片

`POST /file/{fileId}/versions/{versionNo}/inline-images`

```json
{
  "instruction": "在结论后插入示意图",
  "operations": [
    {
      "blockId": "body/p4",
      "imageFileId": 21,
      "imageVersionNo": 1,
      "widthPixels": 640,
      "heightPixels": 360
    }
  ]
}
```

- 单次插入 1～20 张图片。
- 目标必须是正文或表格单元格中的段落。
- 图片文件和明确版本必须属于当前用户。
- 当前仅支持 PNG 和 JPEG。
- 宽高必填，范围均为 1～10000 像素。

图片会以内嵌方式追加到目标段落，成功时创建新版本。

## 5. 异步任务

当前任务框架已经支持提交、排队、状态流转、进度、取消、超时和重试，但默认启用的是占位处理器。占位处理器会更新进度和步骤，不会真正执行 OCR、转换、AI 编辑、导出或图片生成，也不会产生输出文件。

调度与实际处理使用独立的有界线程池，`res.task` 的线程数和队列容量分别应用于两个池。队列满或执行器关闭时，不在请求线程中执行耗时处理，而是将任务记录为 `FAILED`，`errorCode=QUEUE_FULL`，并提示稍后通过现有重试入口重试。若提交时已发生拒绝，创建任务响应中的任务状态也会反映该失败；接受入队后的状态变化通过任务查询获取。

自动重试仅针对处理器异常，最多重试 `maxRetry` 次；超时、取消、执行线程中断和队列拒绝均不自动重试。自动重试的步骤号接续同一任务的历史步骤，失败现场保留。取消任务保留实际进度，成功任务才以 100% 收尾。超时和取消会关闭该次执行上下文并发送线程中断，真实处理器仍须在耗时边界及保存产物前检查取消或中断状态。

### 5.1 提交任务

`POST /task`

```json
{
  "taskType": "AI_EDIT",
  "fileId": 10,
  "inputVersion": 3,
  "instruction": "把内容改写得更正式",
  "idempotencyKey": "7de4494f-9514-4d31-85dc-4fd72bde54ab",
  "expectVersion": 3,
  "timeoutSeconds": 600
}
```

| 字段 | 必填 | 约束/说明 |
| --- | --- | --- |
| `taskType` | 是 | `OCR`、`CONVERT`、`AI_EDIT`、`EXPORT`、`IMAGE_GEN` |
| `fileId` | 否 | 关联当前用户自己的文件；纯生成任务可不传 |
| `inputVersion` | 否 | 大于等于 1；未传时使用文件当前最新版本；没有 `fileId` 时不得传 |
| `instruction` | 否 | 最长 2000 |
| `idempotencyKey` | 否 | 最长 128；同一用户使用相同键重试提交时返回已有任务 |
| `expectVersion` | 否 | 大于等于 0；字段已定义，但当前提交实现尚未使用它做冲突判断 |
| `timeoutSeconds` | 否 | 10～3600；未传时默认 600 秒 |

成功时 `data` 为 [Task](#65-task)。

### 5.2 任务列表

`GET /task/list`

| 查询参数 | 默认值 | 约束/说明 |
| --- | --- | --- |
| `page` | `1` | 大于等于 1 |
| `size` | `20` | 1～100 |
| `status` | - | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` |
| `taskType` | - | `OCR`、`CONVERT`、`AI_EDIT`、`EXPORT`、`IMAGE_GEN` |
| `fileId` | - | 按文件过滤 |

返回 `PageResult<Task>`，按创建时间倒序。目前没有 SSE 进度端点，前端应轮询本接口或任务详情。

### 5.3 任务详情

`GET /task/{taskId}`

返回：

```json
{
  "task": {},
  "steps": [
    {
      "stepNo": 1,
      "stepName": "解析请求",
      "status": "SUCCEEDED",
      "detail": "参数校验通过",
      "errorMessage": null,
      "startTime": "2026-10-11T14:30:00",
      "finishTime": "2026-10-11T14:30:01",
      "durationMs": 1000
    }
  ]
}
```

步骤状态可能为 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`SKIPPED`。

### 5.4 取消任务

`POST /task/{taskId}/cancel`

请求体为空。`PENDING` 任务会直接取消；`RUNNING` 任务设置取消标记，由执行器在步骤边界处理；终态任务重复取消时直接返回当前状态。

### 5.5 重试任务

`POST /task/{taskId}/retry`

请求体为空。只允许重试 `FAILED` 或 `CANCELLED` 任务；成功任务或非终态任务返回 `409`。达到最大重试次数时返回 `429`。重试会创建新任务记录，不复用原幂等键。

### 5.6 文件处理历史

`GET /task/by-file/{fileId}`

返回该文件关联的 `Task[]`，按创建时间倒序。

## 6. 数据结构

### 6.1 UserInfo

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 用户 ID |
| `username` | string | 用户名 |
| `phone` | string/null | 手机号 |
| `role` | string | 当前常见值为 `USER`、`ADMIN` |
| `status` | string | 当前常见值为 `ENABLED`、`DISABLED` |
| `balance` | number | 账户余额 |
| `createTime` | string | 创建时间 |
| `lastLoginTime` | string/null | 最近登录时间 |

### 6.2 TokenPair

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `accessToken` | string | Access Token |
| `refreshToken` | string | Refresh Token |
| `expiresIn` | long | Access Token 剩余有效秒数 |
| `refreshExpiresIn` | long | Refresh Token 剩余有效秒数 |

### 6.3 FileAsset

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 文件 ID |
| `originalName` | string | 初始文件名 |
| `displayName` | string | 当前展示名 |
| `contentType` | string | 当前最新版本的 MIME 类型 |
| `extension` | string | 当前最新版本的规范化扩展名，不带点 |
| `sizeBytes` | long | 当前最新版本内容大小 |
| `sha256` | string | 当前最新版本内容摘要 |
| `source` | string | `UPLOAD` 或 `GENERATED` |
| `status` | string | `READY`、`DELETED` 或 `QUARANTINED` |
| `latestVersion` | integer | 当前最新版本号 |
| `createTime` | string | 创建时间 |
| `updateTime` | string | 更新时间 |

服务端不会向客户端返回内部 `storageKey`。

### 6.4 Version

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 版本记录 ID |
| `fileId` | long | 所属文件 ID |
| `versionNo` | integer | 版本号，从 1 开始 |
| `parentVersion` | integer/null | 来源版本号 |
| `contentType` | string | 该版本独立保存的 MIME 类型 |
| `extension` | string | 该版本独立保存的规范化扩展名，不带点 |
| `sizeBytes` | long | 该版本字节数 |
| `sha256` | string | 该版本 SHA-256 |
| `instruction` | string/null | 创建该版本时的操作说明 |
| `resultSummary` | string/null | 服务端生成的结果摘要 |
| `changeType` | string | `UPLOAD`、`AI_EDIT`、`MANUAL_EDIT`、`CONVERT`、`RESTORE` |
| `createTime` | string | 创建时间 |

### 6.5 Task

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 任务 ID |
| `fileId` | long/null | 关联文件 ID |
| `taskType` | string | 任务类型 |
| `status` | string | 任务状态 |
| `progress` | integer | 进度，0～100 |
| `currentStep` | string/null | 当前步骤 |
| `instruction` | string/null | 用户指令 |
| `errorCode` | string/null | 脱敏错误码 |
| `errorMessage` | string/null | 脱敏错误信息 |
| `inputVersion` | integer/null | 输入版本 |
| `outputVersion` | integer/null | 输出版本 |
| `retryCount` | integer | 已自动重试次数 |
| `maxRetry` | integer | 最大自动重试次数 |
| `cancelRequested` | boolean | 是否已请求取消 |
| `createTime` | string | 创建时间 |
| `startTime` | string/null | 开始时间 |
| `finishTime` | string/null | 结束时间 |
| `elapsedMs` | long/null | 已耗时毫秒 |

任务状态流转：

```text
PENDING -> RUNNING -> SUCCEEDED
   |          |----> FAILED
   |          `----> CANCELLED
   `---------------> CANCELLED
```

终态为 `SUCCEEDED`、`FAILED`、`CANCELLED`。

### 6.6 AdminUser

管理员用户响应不会包含密码或密码哈希，字段为：`id`、`username`、`phone`、`role`、`status`、`balance`、`createTime`、`updateTime`、`lastLoginTime`。

### 6.7 AdminFile

字段与 `FileAsset` 基本相同，额外包含 `userId` 和软删除时间 `deleteTime`。不会返回内部 `storageKey`。

### 6.8 AdminTask

字段与 `Task` 相同，额外包含任务所属的 `userId`。管理员任务详情返回 `task: AdminTask` 和 `steps: TaskStep[]`。

### 6.9 AdminOverview

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `totalUsers` | long | 用户总数 |
| `enabledUsers` / `disabledUsers` | long | 启用、禁用用户数 |
| `adminUsers` | long | 管理员数量 |
| `activeFiles` / `deletedFiles` | long | 未删除、已删除文件数 |
| `storageBytes` | long | 未删除文件占用的存储字节数 |
| `totalTasks` | long | 任务总数 |
| `pendingTasks` / `runningTasks` | long | 等待、运行中的任务数 |
| `succeededTasks` / `failedTasks` / `cancelledTasks` | long | 各终态任务数 |
| `taskFailureRate` | number | `FAILED / (SUCCEEDED + FAILED)`，无完成样本时为 0 |

### 6.10 AdminUserUsage

包含 `userId`、`username`、`role`、`status`、`balance`，以及与 `AdminOverview` 同含义的文件、存储和任务统计字段。该结构只统计资源元数据，不包含文件正文、内部存储路径或密码哈希。

### 6.11 PdfContent

顶层字段包括 `fileId`、`versionNo`、`documentType`、`totalPages`、各页面类型数量、实际返回的 `extractedTextChars`、`textTruncated` 和 `pages`。每个页面包含 `pageNo`、`type`、`text`、截断前的 `textCharCount`、`imageCount` 和 `textTruncated`。

### 6.12 XlsxStructure

顶层包含 `fileId`、`versionNo`、`totalSheets`、`includedCells`、`truncated` 和 `sheets`。每个工作表包含名称、行范围、合并区域、基础图表信息及单元格。每个单元格包含 A1 地址、零基行列下标、类型、值、公式、公式缓存值类型和基础样式；内部存储路径不会返回。

### 6.13 XlsxRange

包含 `fileId`、`versionNo`、`sheetName`、规范化后的 `range`、零基 `firstRow`、`firstColumn`、`rowCount`、`columnCount` 和二维 `rows`。区域内每个位置都有一个 `XlsxStructure.Cell`，未创建的空白位置 `style` 为 `null`。

### 6.14 XlsxFormulaResults

包含 `fileId`、`versionNo`、`sheetName`、规范化后的 `range` 和 `results`。每项包含 `address`、`formula`、`status`、`valueType`、`value`、`message`；状态和错误含义见 3.19。

## 7. 管理员

所有 `/admin/**` 接口都必须携带有效 Access Token，并要求当前账号角色为 `ADMIN`。普通用户访问时返回 HTTP `403`。系统不提供匿名管理员注册或初始化接口；首个管理员需要通过受控部署流程或数据库设置。

### 7.1 用户列表

`GET /admin/users`

| 查询参数 | 默认值 | 说明 |
| --- | --- | --- |
| `page` | `1` | 大于等于 1 |
| `size` | `20` | 1～100 |
| `keyword` | - | 按用户名或手机号模糊查询，最长 100 字符 |
| `status` | - | `ENABLED` 或 `DISABLED` |
| `role` | - | `USER` 或 `ADMIN` |

返回 `PageResult<AdminUser>`，按创建时间倒序。

### 7.2 用户详情

`GET /admin/users/{userId}`

成功时返回 `AdminUser`；用户不存在时返回业务码 `404`。

### 7.3 启用或禁用账号

`PUT /admin/users/{userId}/status`

```json
{
  "status": "DISABLED"
}
```

`status` 只支持 `ENABLED`、`DISABLED`。禁用账号会立即撤销该用户的全部登录会话，而且 JWT 过滤器会在每次请求时检查账号状态。管理员不能禁用自己的账号。变更会写入审计日志。

### 7.4 调整用户角色

`PUT /admin/users/{userId}/role`

```json
{
  "role": "ADMIN"
}
```

`role` 只支持 `USER`、`ADMIN`。角色变化后撤销目标用户的全部会话，使其重新登录后获得新权限。管理员不能把自己的角色降为普通用户。变更会写入审计日志。

### 7.5 跨用户文件列表

`GET /admin/files`

查询参数沿用普通文件列表的 `page`、`size`、`keyword`、`status`，并额外支持可选的 `userId`。未传 `userId` 时查询全部用户；未传 `status` 时默认查询 `READY` 文件。返回 `PageResult<AdminFile>`，按创建时间倒序。

### 7.6 文件详情与当前内容下载

- `GET /admin/files/{fileId}`：返回 `AdminFile`。
- `GET /admin/files/{fileId}/download`：下载文件主记录指向的当前内容。

管理员可访问任意用户文件，但接口始终通过内部文件 ID 定位内容，不接受磁盘路径，也不返回存储标识。查看详情和下载会写入审计日志；已删除文件不能通过当前内容下载接口下载。

### 7.7 文件版本与文档结构

- `GET /admin/files/{fileId}/versions`：返回版本列表。
- `GET /admin/files/{fileId}/versions/{versionNo}`：返回指定版本详情。
- `GET /admin/files/{fileId}/versions/{versionNo}/download`：下载指定版本。
- `GET /admin/files/{fileId}/versions/{versionNo}/structure`：读取指定 DOCX 版本的段落、表格和图片结构。
- `GET /admin/files/{fileId}/versions/{versionNo}/pdf/content`：读取指定 PDF 版本的原生文本和页面类型。
- `GET /admin/files/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview`：渲染指定 PDF 页面的 PNG 预览。
- `GET /admin/files/{fileId}/versions/{versionNo}/xlsx/structure`：读取指定 XLSX 版本的工作表和单元格结构。
- `GET /admin/files/{fileId}/versions/{versionNo}/xlsx/range`：读取指定 XLSX 版本中的矩形区域。
- `GET /admin/files/{fileId}/versions/{versionNo}/xlsx/formula-results`：即时计算指定版本区域内的公式，参数与普通用户接口相同；按真实所有者访问并记录管理员审计。

这些接口使用文件真实所有者完成版本定位，不依赖客户端传入用户 ID。查看或下载行为会写入审计日志。

### 7.8 跨用户任务列表

`GET /admin/tasks`

查询参数沿用普通任务列表的 `page`、`size`、`status`、`taskType`、`fileId`，并额外支持可选的 `userId`。未传 `userId` 时查询全部用户。返回 `PageResult<AdminTask>`，按创建时间倒序，可用于筛选 `FAILED` 任务。

### 7.9 任务与步骤详情

`GET /admin/tasks/{taskId}`

返回带所属用户的任务信息和执行步骤。错误信息仍使用业务层保存的脱敏内容，不返回模型密钥或异常堆栈；查看行为会写入审计日志。

### 7.10 管理文件和任务

- `PUT /admin/files/{fileId}/name`：请求体与普通重命名接口相同，重命名任意用户文件。
- `DELETE /admin/files/{fileId}`：软删除任意用户文件，重复删除保持幂等。
- `POST /admin/tasks/{taskId}/cancel`：取消任意用户的待处理或运行中任务。
- `POST /admin/tasks/{taskId}/retry`：为任意用户的失败或已取消任务创建重试任务。

这些操作先读取资源真实所有者，再复用普通业务服务执行状态和并发检查，客户端不能指定操作所使用的用户 ID。所有操作写入审计日志。

### 7.11 系统与用户用量统计

- `GET /admin/statistics/overview`：返回 `AdminOverview`，用于查看用户数、文件数、存储字节数、各状态任务数和任务失败率。
- `GET /admin/users/{userId}/usage`：返回 `AdminUserUsage`，统计指定用户的文件、存储和任务使用情况；用户不存在时返回业务码 `404`。

存储量只累计未软删除文件；失败率按 `FAILED / (SUCCEEDED + FAILED)` 计算，取消任务不计入成功或失败样本。两个查询均为只读聚合查询，并记录管理员审计日志。

### 7.12 公共模板管理

以下均为现有管理员接口，普通用户无权调用：

| 接口 | 请求与结果 |
| --- | --- |
| `GET /admin/templates` | `page=1`、`size=20`（1～100），可选 `enabled=true/false`、`category`（最多 32 字符）、`keyword`（最多 100 字符）；返回 `PageResult<DocTemplateVO>` |
| `GET /admin/templates/{id}` | 返回 `DocTemplateVO` |
| `POST /admin/templates` | multipart：元数据和必填 `file`；创建第 1 版，默认停用 |
| `PUT /admin/templates/{id}` | JSON 元数据；更新名称、标识、分类、说明和变量定义 |
| `POST /admin/templates/{id}/content` | multipart：必填 `file`；替换当前内容，版本号原子递增 |
| `PUT /admin/templates/{id}/enabled?enabled=true` | 启用或停用；未上传内容时不能启用 |
| `DELETE /admin/templates/{id}` | 删除停用模板的记录；启用中的模板返回业务码 `409` |
| `GET /admin/templates/{id}/download` | 下载当前内容供管理员核对；不接受任意存储路径 |

创建与更新元数据均使用以下字段，创建时放在 multipart 表单中：

```json
{
  "code": "weekly-report",
  "name": "周报模板",
  "category": "报告",
  "description": "周报内容",
  "variables": "[{\"name\":\"title\",\"label\":\"标题\",\"required\":true}]"
}
```

`code`、`name` 必填，分别最多 64、128 字符；code 只允许字母、数字、下划线和连字符，重复返回 `409`。可选 category、description 分别最多 32、500 字符，variables 原始字符串最多 20,000 字符。

variables 是 **JSON 数组字符串**，null、空白或 JSON null 代表无变量。最多 100 个变量，每个元素必须是对象：name 必须是字符串，去除首尾空格后保存，1～64 字符、以字母或下划线开头，其余只允许字母、数字和下划线；名称区分大小写，规范化后不得重复。label、description 可省略或为 null，否则必须是字符串，分别最多 128、255 字符；required 可省略，提供时必须是 JSON 布尔值。校验失败返回业务码 `400`，创建时在上传落盘前完成变量校验。

`DocTemplateVO` 包含 id、code、name、category、description、originalName、contentType、extension、sizeBytes、versionNo、variables、enabled、createTime、updateTime、hasFile，不含内部 storageKey。文件类型和结构校验复用普通上传规则。创建、替换操作失败或事务明确回滚时清理本次新文件；替换前的旧文件保留。

当前只保存最新模板元数据。尚无模板历史版本查询/回退、变量填充生成或生成预览接口，也没有旧模板文件的正式存储回收任务；临时目录清理任务不处理这些文件。

## 8. AI 回复与确认编辑

原有会话 CRUD、`GET /ai/sessions/{sessionId}/messages` 和 `POST .../messages` 保持原语义：后者只保存一条用户消息。需要模型回复时使用下面的新接口，不要先调用旧消息接口重复记录同一条指令。

### 8.1 生成回复

`POST /ai/sessions/{sessionId}/replies`，需要登录、会话归属及 ACTIVE 状态。新流程要求 `res.ai.require-confirmation=true`，关闭时返回业务码 `503`。支持纯对话、DOCX 编辑，以及 XLSX 单元格公式计划（见 8.6）。绑定文件时均要求明确版本。调用同步返回，尚未提供 SSE。

```json
{
  "content": "把第二段改得更正式，保留表格和图片",
  "versionNo": 1,
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 字段 | 规则 |
| --- | --- |
| `content` | 必填，非空，最多 2000 字符；去除首尾空白后保存 |
| `versionNo` | 绑定文件时必填，必须为明确的正版本号；纯对话不允许传入 |
| `idempotencyKey` | 必填，非空，最多 128 字符，按会话隔离；一次发送意图对应一个稳定键 |

同一幂等键与相同内容/版本重放时返回原回复，不再次调用模型；键被用于其他内容或版本时返回业务码 `409`。同一会话已有回复正在生成时，其他键的新请求返回 `409`。请求超时后重发应沿用原键；若返回 `RUNNING`，使用查询接口检查进度，不要自动换键重新调用模型。

新回复会保存一条 USER 消息和一条 ASSISTANT 消息。模型调用在数据库事务外执行。历史最多读取最近 20 条用户/已完成助手消息，总字符数不超过 12000，按完整消息取最近内容并保持角色和时间顺序，不接受历史 SYSTEM/TOOL 内容作为新系统指令。

统一响应的 `data` 类型为 `AiReply`：

```json
{
  "message": {
    "id": 102, "sessionId": 7, "role": "ASSISTANT",
    "content": "已生成待确认的修改计划", "modelName": "stub",
    "promptTokens": 20, "completionTokens": 10, "durationMs": 50
  },
  "runStatus": "COMPLETED",
  "planStatus": "PENDING",
  "sourceVersion": 1,
  "outputVersion": null,
  "result": {
    "reply": "已生成待确认的修改计划",
    "steps": [],
    "plans": [{"summary": "修改段落", "operations": [
      {"type": "REPLACE_TEXT", "target": "body/p1", "content": "修改后的段落", "reason": "改为正式表述"}
    ], "expected": null}],
    "usage": {"modelCalls": 2, "toolCalls": 1, "inputTokens": 20, "outputTokens": 10,
      "estimatedCost": 0, "costConfigured": false, "elapsedMs": 50},
    "termination": "COMPLETED", "truncated": false
  },
  "changes": [{"type": "REPLACE_TEXT", "target": "body/p1",
    "before": "原始段落", "after": "修改后的段落", "reason": "改为正式表述"}]
}
```

示例中的 `modelName=stub` 仅为桩模型，不代表真实模型已验收。模型不可用、调用失败或中断时，回复记录为 `runStatus=FAILED`，`planStatus=NONE`，说明保存在回复中；HTTP 成功返回数据不代表模型执行成功，前端必须检查状态。合并计划冲突时 `termination=INVALID_PLAN`，保留已收到的用量，不能确认该结果。未配置计价时 `costConfigured=false`，不能将 `estimatedCost=0` 显示成免费。每日调用额度见 8.5，当前不扣余额。

### 8.2 查询、确认与放弃

`GET /ai/sessions/{sessionId}/replies/{messageId}` 返回同一 `AiReply`。这里的 `messageId` 是生成接口返回的助手消息 ID。跨用户会话、跨会话消息或普通旧消息返回业务码 `404`。

| 状态 | 含义与可用操作 |
| --- | --- |
| `runStatus=RUNNING` | 回复尚未完成，可重查 |
| `runStatus=FAILED` | 本次回复失败；需重试时发送新请求并使用新幂等键 |
| `planStatus=NONE` | 没有待处理修改，不能确认或放弃 |
| `planStatus=PENDING` | 可查看 `changes` 并确认或放弃 |
| `planStatus=CONFIRMED` | 修改已保存，`outputVersion` 是产出版本；重复确认不再写版本 |
| `planStatus=DISCARDED` | 已放弃；重复放弃幂等，不能再确认 |

`changes` 只在 PENDING 时提供：替换展示 before/after，插入的 before 为 null、target 是参照块，删除的 after 为 null。这是内容差异预览，不是尚未保存版本的页面排版预览。按钮可用性以 `planStatus` 为准；`result.plans` 保留原始生成结果用于追溯。

`POST /ai/sessions/{sessionId}/replies/{messageId}/confirm`，无需请求体。服务端从已保存回复中读取计划，使用会话绑定的文件和保存的 `sourceVersion`，不会使用客户端提供的计划、文件 ID、版本或用户 ID。会话必须 ACTIVE；同一计划的确认/放弃由数据库行锁串行处理。段落、单元格、图片与格式修改合并成一次原子编辑，成功仅生成一个 `AI_EDIT` 版本；回复状态和版本写入处于同一事务，回滚时清理文件产物。

若源版本已过期，返回业务码 `409`，原计划仍为 PENDING，不能强制覆盖新版本。用户应查看最新版本，再放弃旧计划并用新键重新发送指令。确认成功后使用 `outputVersion` 调用既有按版本预览、下载及 PDF 导出；修改前后比较使用 `sourceVersion` 与 `outputVersion`，不要改用“最新版本”下载。

`POST /ai/sessions/{sessionId}/replies/{messageId}/discard`，无需请求体，不生成文档版本。继续调整时可放弃旧计划，再发送新的自然语言要求。已确认计划不能再放弃，已放弃计划不能再确认，均返回业务码 `409`。

数据库需应用 `V5__ai_reply_confirmation.sql`。启动恢复沿用 `res.task.recover-on-startup`：开启时将遗留 RUNNING 回复标记为 FAILED，并提示使用新键重发；与现有任务恢复相同，该模式用于单实例，多实例需关闭启动恢复并另行设计分布式恢复。

### 8.3 待确认的 DOCX 图片插入

图片插入复用以上四个回复接口，不新增 HTTP 路径。用户需要提供已上传的 PNG/JPEG 文件 ID 和明确版本；`insert_image` 工具在默认确认模式下只产出计划，不写文档。计划生成时校验图片归属、版本及格式，确认时再次读取同一图片版本校验。即使图片已有更新版本，也不改用最新版本；图片已不可用或不再符合条件时，整次混合编辑失败，不保存部分文本或图片。

服务端返回的 `result.plans[].operations[]` 新增 `INSERT_IMAGE` 类型和可选 `image` 字段；这不是让客户端提交编辑计划的新请求体：

```json
{
  "type": "INSERT_IMAGE",
  "target": "body/p0",
  "content": null,
  "reason": "添加说明图片",
  "image": {"fileId": 30, "versionNo": 2, "widthPixels": 80, "heightPixels": 40}
}
```

`target` 必须是源 DOCX 中的段落，图片以内嵌方式追加到该段落，保留原段落文本和其他未涉及内容。图片 ID 和版本必须为正数，显示宽高必须在 1～10000 像素；工具未指定宽高时默认 400×300，显式越界会拒绝，不会静默调整。单个计划最多 50 个总操作，其中图片不超过 20 张；同一目标不能同时替换文本和插图，也不能同时替换单元格及其内部插图目标。

`changes[]` 中的图片项包含同样的 `image` 对象，`before`/`after` 为 null，前端应按 `type=INSERT_IMAGE` 展示“追加图片”、显示尺寸，并使用 image.fileId/image.versionNo 对应的既有预览或下载入口展示图片。不要将它解释成删除或普通文本替换。

图片与段落、表格操作可合并确认，只产生一个 `AI_EDIT` 版本；重复确认复用该输出版本，放弃则不生成版本。旧纯文本计划的 `image` 缺失或为 null，仍可读取；其他类型不允许夹带图片参数。本次仅扩展 V5 已有的 JSON 结果结构，不新增数据库迁移。新 HTTP 回复流程仍要求提交前确认；显式关闭确认的内部兼容模式保留原直接插图路径。

### 8.4 待确认的 DOCX 基础格式

基础格式复用现有 `edit_paragraph` 工具和四个回复接口，不新增 HTTP 路径或数据库迁移。工具参数为 `operation=FORMAT_PARAGRAPH`、目标 `target`、`formatType`，标题另传整数 `level`；不使用文本 `content`。服务端保存的计划如下：

```json
{
  "type": "FORMAT_PARAGRAPH",
  "target": "body/p0",
  "content": null,
  "reason": "将首段设为二级标题",
  "format": {"type": "SET_HEADING", "level": 2}
}
```

| `format.type` | 效果 |
| --- | --- |
| `SET_HEADING` | 设置 Heading1～Heading9 并清除直接列表编号；level 必须为 1～9 |
| `SET_NORMAL` | 设置 Normal 样式并清除直接列表编号 |
| `SET_BULLET` | 设置项目符号列表，保留段落样式 |
| `SET_NUMBERED` | 设置十进制编号列表，保留段落样式 |
| `CLEAR_LIST` | 仅清除直接列表编号，保留段落样式 |

除 SET_HEADING 外不能传 level。不接受任意样式名或脚本。格式操作保留段落文本；同一段落允许一次格式与一次 REPLACE_TEXT 或 INSERT_IMAGE 组合，其他重复目标及删除组合会被拒绝。同一计划中新建的同类列表共用编号定义，不接续源文件中其他列表。

`changes[]` 中格式项的 before/after 为 null，前端通过 `type=FORMAT_PARAGRAPH` 区分，展示 `beforeFormat={styleId,headingLevel,numberingFormat}` 与目标 `format`。编号类型可能为 bullet、decimal 或 null；这些字段反映基础段落属性，不展开全部继承样式。DOCX 结构的段落块也增加可选 `numberingFormat` 字段，read_document 工具向模型提供原样式与列表类型。旧计划不含 format 时仍可读取；其他操作不能携带该字段。确认后与其他操作一同生成单个 AI_EDIT 版本，失败不保存部分结果。现有按版本 compare 接口仍只比较内容，纯格式变化应查看回复中的格式差异及保存后的页面预览。

### 8.5 每用户每日调用额度

`AI_DAILY_CALL_LIMIT` 对应 `res.ai.daily-call-limit-per-user`：0 为不限额，正数限制每用户每日进入 ChatModel 的调用尝试次数。包含失败、智能体重试和桩模型调用；不包含模型客户端内部的 HTTP 重试。查询、确认、放弃及同键幂等重放不额外耗额，不扣减 balance。

每次模型调用前使用 Redis Lua 原子占额；失败不退还额度。按 `AI_QUOTA_ZONE`（默认 Asia/Shanghai）的自然日分键，跨实例需使用同一 Redis、额度与时区配置。计数键的 48 小时 TTL 仅回收历史数据，不是滚动额度窗口。生产环境额度数值需部署时确定；启用正数额度时必须有可用的 Redis。

生成回复达到上限返回业务码 `429`，`result.termination=QUOTA_EXCEEDED`；Redis 检查失败返回业务码 `503`，termination 为 QUOTA_UNAVAILABLE，均停止后续模型调用。沿用统一响应的 HTTP 200/业务码约定，**data 仍包含已持久化的 AiReply**：runStatus=FAILED、planStatus=NONE，可读取 message.id 和实际 usage，不能确认中途产生的计划。GET 查询失败回复时返回业务码 0，失败状态保持不变。

重复提交原幂等键返回原失败回复与相同业务码，不重新调用模型。额度恢复后再次发送需由用户发起并使用新键；前端不能自动换键重试。真实 Redis 并发与供应商用量的集成验收尚未完成。

### 8.6 待确认的 XLSX 单元格公式

复用回复生成、查询、确认和放弃四个接口，不新增 HTTP 路径或数据库迁移。将会话绑定已有 XLSX 文件，以明确 versionNo 发送自然语言指令；模型通过 read_document 读取当前版本的工作表、列名和实际单元格内容，再调用 set_formula。真实模型的自然语言效果尚未验收，桩模型完整链路已覆盖。

set_formula 工具每次接受一个 sheetName、一个 A1 格式的 cell、formula 和必填说明 reason。工作表必须真实存在，目标可以是空白单元格；公式可带开头等号，保存计划时去除该等号。工具不接收用户 ID、文件 ID 或版本，均由已认证上下文提供。一次回复可收集多个单元格计划，但总操作数不得超过 50 或配置的 XLSX 编辑上限；同一目标不允许重复写入，大小写及绝对引用别名也视为同一目标。

`result.plans[].operations[]` 示例：

```json
{
  "type": "SET_FORMULA",
  "target": "数据!C2",
  "content": "SUM(A2:A3)",
  "reason": "汇总金额列的两条实际数据"
}
```

target 是内部定位形式“原始工作表名称!规范单元格”，单元格使用大写、不含绝对引用标记；工作表名称自身包含 ! 时，最后一个 ! 才是分隔符。它不是待执行的 Excel 公式表达式，带空格的工作表名称也不额外加引号。公式 content 中跨表引用仍遵循 Excel 自身的引号规则。

对应 changes 项展示实际源单元格内容：

```json
{
  "type": "SET_FORMULA",
  "target": "数据!C2",
  "before": "=A2*2",
  "after": "=SUM(A2:A3)",
  "reason": "汇总金额列的两条实际数据"
}
```

原内容为公式时 before 带等号；普通值返回原值文本，空白单元格为 null。after 为带等号的目标公式。差异直接读取明确源版本中的目标单元格，即使整表结构因读取上限被截断，也不会把未读到的已有内容当作空白。截断仍影响模型能看到的数据范围，不能据此声称完整分析了整张表。客户端不重传或编辑服务端计划。

生成计划时和确认时都复用已有 XLSX 公式编辑检查：限制公式长度为 8192 字符、语法、直接工作表引用、行列边界及已限制的外部引用/危险函数；拒绝受保护工作表、非合并区域左上角及数组公式单元格。DOCX 与 XLSX 操作不能混为同一计划。模型工具参数无效时作为 FAILURE 步骤回填模型，不形成可确认计划；合并计划无效时回复为 FAILED/INVALID_PLAN。

确认只读取已持久化计划，成功一次写入一个 AI_EDIT 版本；保留目标基础样式及其他单元格的值和公式。任一操作失败都不保存部分结果，版本冲突返回 409，外层事务回滚时清理产物。重复确认返回原 outputVersion；放弃不写版本。确认后复用既有 XLSX 按版本预览、比较、下载及公式结果接口。

本次写入不自动重算整个工作簿的公式缓存，而是标记办公软件打开时重算；差异中的公式不代表已获得计算结果。需要即时计算时使用既有 formula-results 接口，仍受 POI 不支持的函数、动态引用、动态数组及 1904 日期系统等既有边界限制。区域公式填充、普通值写入、工作表或行列调整仍使用已有人工编辑接口，尚未接入该 AI 计划。

## 9. 当前实现边界

- 已提供同步 AI 回复、DOCX 文本/图片/基础格式及 XLSX 单元格公式差异、确认与放弃接口，以及可配置的每日调用额度；真实模型效果尚未验收，流式响应和前端页面尚未实现。
- LangChain4j 工具循环已接入回复 Controller；尚未接入异步任务处理器。
- 异步任务默认使用占位处理器，不产生真实处理结果。
- 尚未提供 SSE 或 WebSocket 进度推送。
- DOCX 生成样式目前固定，尚不支持用户模板、主题配置和自由页面参数。
- DOCX 块 ID 不是跨版本永久 ID，每次成功编辑后应重新读取结构。
- 验证码开发配置使用模拟发送，不能直接视为生产短信能力。
- 管理员可跨用户读取、下载、重命名或软删除文件，并查看、取消或重试任务；尚未提供跨用户 DOCX 内容编辑和版本恢复入口，以免在当前版本模型中错误记录实际操作者。
- PDF 页面分类是本地启发式检测；扫描页尚未接入 OCR，矢量图形中的文字也不会作为原生文本返回。
