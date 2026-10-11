# Documenter HTTP API 文档

本文档根据当前 `dev` 工作区中的 Controller、DTO、VO、安全配置和服务实现整理，描述当前已经存在的 HTTP 接口，不包含 TODO 中尚未实现的接口。

- 默认服务地址：`http://localhost:8080`
- 默认数据格式：`application/json; charset=UTF-8`
- 时间格式：ISO-8601 本地时间，例如 `2026-10-11T14:30:00`
- 文档生成日期：2026-10-11

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
| `429` | 发送、上传或重试次数达到限制 |
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
| 文件 | GET | `/file/{fileId}/download` | 是 | 下载文件记录对应内容 |
| DOCX | POST | `/file/docx/generate` | 是 | 按结构生成 DOCX |
| 版本 | GET | `/file/{fileId}/versions` | 是 | 版本列表 |
| 版本 | GET | `/file/{fileId}/versions/{versionNo}` | 是 | 版本详情 |
| 版本 | GET | `/file/{fileId}/versions/{versionNo}/download` | 是 | 下载指定版本 |
| DOCX | GET | `/file/{fileId}/versions/{versionNo}/structure` | 是 | 读取 DOCX 结构 |
| PDF | GET | `/file/{fileId}/versions/{versionNo}/pdf/content` | 是 | 提取 PDF 原生文本并检测页面类型 |
| PDF | GET | `/file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview` | 是 | 渲染 PDF 指定页 PNG 预览 |
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

当前实现从文件主记录保存的内容位置下载。需要稳定获取某个明确版本时，应使用 `/file/{fileId}/versions/{versionNo}/download`。

### 3.7 版本列表

`GET /file/{fileId}/versions`

返回 `Version[]`，按版本号倒序。版本号从 1 开始。

### 3.8 版本详情

`GET /file/{fileId}/versions/{versionNo}`

成功时 `data` 为 [Version](#64-version)。

### 3.9 下载指定版本

`GET /file/{fileId}/versions/{versionNo}/download`

直接返回二进制文件。下载文件名自动带版本后缀，例如 `report.docx` 的版本 2 下载为 `report-v2.docx`。

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

恢复不会覆盖或删除历史，而是复制目标版本内容，创建一个新的 `RESTORE` 版本。成功时 `data` 为新 [Version](#64-version)。若 `expectVersion` 与当前版本不一致，返回业务码 `409`。

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
| `contentType` | string | 服务端识别的 MIME 类型 |
| `extension` | string | 规范化扩展名，不带点 |
| `sizeBytes` | long | 文件主记录内容大小 |
| `sha256` | string | 文件主记录内容摘要 |
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

## 8. 当前实现边界

- 尚未提供 AI 对话或自然语言文档编辑 HTTP 接口。
- 尚未把 LangChain4j 工具调用接入 Controller 或任务处理器。
- 异步任务默认使用占位处理器，不产生真实处理结果。
- 尚未提供 SSE 或 WebSocket 进度推送。
- DOCX 生成样式目前固定，尚不支持用户模板、主题配置和自由页面参数。
- DOCX 块 ID 不是跨版本永久 ID，每次成功编辑后应重新读取结构。
- 验证码开发配置使用模拟发送，不能直接视为生产短信能力。
- 管理员可跨用户读取、下载、重命名或软删除文件，并查看、取消或重试任务；尚未提供跨用户 DOCX 内容编辑和版本恢复入口，以免在当前版本模型中错误记录实际操作者。
- PDF 页面分类是本地启发式检测；扫描页尚未接入 OCR，矢量图形中的文字也不会作为原生文本返回。
