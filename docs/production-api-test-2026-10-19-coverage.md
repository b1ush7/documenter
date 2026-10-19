# 正式环境接口测试覆盖明细

“已请求”仅表示实际请求覆盖，不代表该接口所有功能和权限组合均通过。管理端请求只验证普通用户被拒绝；账号和验证码的部分接口仅验证缺参拒绝，不发送短信或修改密码。

| 方法 | 路径 | 分组 | 请求覆盖 | HTTP/业务码 |
| --- | --- | --- | --- | --- |
| DELETE | `/admin/files/{fileId}` | 管理端·文件与版本 | 未请求 |  |
| DELETE | `/admin/model-configs/{id}` | 管理端·模型配置 | 未请求 |  |
| DELETE | `/admin/templates/{id}` | 管理端·公共模板 | 未请求 |  |
| DELETE | `/ai/sessions/{sessionId}` | AI 会话与消息 | 已请求 | 200/0 |
| DELETE | `/file/{fileId}` | 文件管理 | 已请求 | 200/0 |
| GET | `/admin/audit-logs` | 管理端·统计与审计 | 已请求 | 403/403 |
| GET | `/admin/files` | 管理端·文件与版本 | 已请求 | 403/403 |
| GET | `/admin/files/{fileId}` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/download` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/download` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/pages/{pageNo}/preview` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/pdf/content` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/preview` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/structure` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/formula-results` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/range` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/files/{fileId}/versions/{versionNo}/xlsx/structure` | 管理端·文件与版本 | 未请求 |  |
| GET | `/admin/model-configs` | 管理端·模型配置 | 已请求 | 403/403 |
| GET | `/admin/model-configs/{id}` | 管理端·模型配置 | 未请求 |  |
| GET | `/admin/statistics/overview` | 管理端·统计与审计 | 已请求 | 403/403 |
| GET | `/admin/tasks` | 管理端·任务 | 已请求 | 403/403 |
| GET | `/admin/tasks/{taskId}` | 管理端·任务 | 未请求 |  |
| GET | `/admin/templates` | 管理端·公共模板 | 已请求 | 403/403 |
| GET | `/admin/templates/{id}` | 管理端·公共模板 | 未请求 |  |
| GET | `/admin/templates/{id}/download` | 管理端·公共模板 | 未请求 |  |
| GET | `/admin/templates/{id}/versions` | 管理端·公共模板 | 未请求 |  |
| GET | `/admin/templates/{id}/versions/{versionNo}/download` | 管理端·公共模板 | 未请求 |  |
| GET | `/admin/users` | 管理端·用户 | 已请求 | 403/403 |
| GET | `/admin/users/{userId}` | 管理端·用户 | 未请求 |  |
| GET | `/admin/users/{userId}/usage` | 管理端·统计与审计 | 未请求 |  |
| GET | `/ai/sessions` | AI 会话与消息 | 已请求 | 200/0 |
| GET | `/ai/sessions/{sessionId}` | AI 会话与消息 | 已请求 | 200/0 |
| GET | `/ai/sessions/{sessionId}/messages` | AI 会话与消息 | 已请求 | 200/0 |
| GET | `/ai/sessions/{sessionId}/replies/{messageId}` | AI 回复与确认 | 已请求 | 200/0 |
| GET | `/file/list` | 文件管理 | 已请求 | 200/0, 200/400 |
| GET | `/file/{fileId}` | 文件管理 | 已请求 | 200/0 |
| GET | `/file/{fileId}/download` | 文件版本与下载 | 已请求 | 200/400 |
| GET | `/file/{fileId}/versions` | 文件版本与下载 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{fromVersion}/compare/{toVersion}` | 文件版本与下载 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}` | 文件版本与下载 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/download` | 文件版本与下载 | 已请求 | 200/None |
| GET | `/file/{fileId}/versions/{versionNo}/export/pdf` | 预览与导出 | 已请求 | 200/None |
| GET | `/file/{fileId}/versions/{versionNo}/footnotes` | DOCX 生成与编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/header-footer-links` | DOCX 生成与编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/ocr/corrections/{fingerprint}` | OCR 识别与校正 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/pages/{pageNo}/preview` | 预览与导出 | 已请求 | 200/None |
| GET | `/file/{fileId}/versions/{versionNo}/pdf/content` | PDF 处理 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview` | 预览与导出 | 已请求 | 200/None |
| GET | `/file/{fileId}/versions/{versionNo}/preview` | 预览与导出 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/structure` | DOCX 生成与编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/table-of-contents` | DOCX 生成与编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/xlsx/formula-results` | XLSX 表格编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/xlsx/range` | XLSX 表格编辑 | 已请求 | 200/0 |
| GET | `/file/{fileId}/versions/{versionNo}/xlsx/structure` | XLSX 表格编辑 | 已请求 | 200/0 |
| GET | `/task/by-file/{fileId}` | 异步任务 | 已请求 | 200/0 |
| GET | `/task/list` | 异步任务 | 已请求 | 200/0 |
| GET | `/task/{taskId}` | 异步任务 | 已请求 | 200/0 |
| GET | `/templates` | 公共模板 | 已请求 | 200/0 |
| GET | `/user/me` | 用户与认证 | 已请求 | 200/0, 401/401 |
| POST | `/admin/model-configs` | 管理端·模型配置 | 未请求 |  |
| POST | `/admin/storage/recycle` | 管理端·存储回收 | 未请求 |  |
| POST | `/admin/tasks/{taskId}/cancel` | 管理端·任务 | 未请求 |  |
| POST | `/admin/tasks/{taskId}/retry` | 管理端·任务 | 未请求 |  |
| POST | `/admin/templates` | 管理端·公共模板 | 未请求 |  |
| POST | `/admin/templates/{id}/content` | 管理端·公共模板 | 未请求 |  |
| POST | `/admin/templates/{id}/versions/{versionNo}/restore` | 管理端·公共模板 | 未请求 |  |
| POST | `/ai/sessions` | AI 会话与消息 | 已请求 | 200/0 |
| POST | `/ai/sessions/{sessionId}/archive` | AI 会话与消息 | 已请求 | 200/0 |
| POST | `/ai/sessions/{sessionId}/messages` | AI 会话与消息 | 已请求 | 200/0, 200/409 |
| POST | `/ai/sessions/{sessionId}/replies` | AI 回复与确认 | 已请求 | 200/0, 200/400, 200/409 |
| POST | `/ai/sessions/{sessionId}/replies/stream` | AI 回复与确认 | 已请求 | 200/None |
| POST | `/ai/sessions/{sessionId}/replies/{messageId}/confirm` | AI 回复与确认 | 已请求 | 200/0, 200/409 |
| POST | `/ai/sessions/{sessionId}/replies/{messageId}/discard` | AI 回复与确认 | 已请求 | 200/0 |
| POST | `/file/docx/generate` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/upload` | 文件管理 | 已请求 | 200/0, 400/400 |
| POST | `/file/{fileId}/versions/{versionNo}/compatibility-check` | 预览与导出 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/footnotes` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/header-footer-links` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/image-edits` | 图片处理 | 已请求 | 200/0, 200/400 |
| POST | `/file/{fileId}/versions/{versionNo}/inline-images` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/ocr` | OCR 识别与校正 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/ocr/corrections/{fingerprint}/organize` | OCR 识别与校正 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/ocr/text` | OCR 识别与校正 | 已请求 | 200/None |
| POST | `/file/{fileId}/versions/{versionNo}/paragraph-edits` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/paragraph-formats` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/paragraph-text` | DOCX 生成与编辑 | 已请求 | 200/0, 200/400, 200/409 |
| POST | `/file/{fileId}/versions/{versionNo}/pdf/convert-to-docx` | PDF 处理 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/restore` | 文件版本与下载 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/table-edits` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/table-of-contents` | DOCX 生成与编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/cell-edits` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/data-edits` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/formats` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/images` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/sheet-edits` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/file/{fileId}/versions/{versionNo}/xlsx/structure-edits` | XLSX 表格编辑 | 已请求 | 200/0 |
| POST | `/task` | 异步任务 | 已请求 | 200/0, 200/400 |
| POST | `/task/{taskId}/cancel` | 异步任务 | 已请求 | 200/0 |
| POST | `/task/{taskId}/retry` | 异步任务 | 已请求 | 200/0, 200/409 |
| POST | `/user/change-password` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/login` | 用户与认证 | 已请求 | 200/0, 200/400 |
| POST | `/user/login/by-code` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/login/code` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/logout` | 用户与认证 | 已请求 | 200/0 |
| POST | `/user/logout-all` | 用户与认证 | 未请求 |  |
| POST | `/user/refresh` | 用户与认证 | 已请求 | 200/0, 200/401 |
| POST | `/user/register` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/register/by-code` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/register/code` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/reset-password` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/reset-password/code` | 用户与认证 | 已请求 | 200/400 |
| POST | `/user/verify/code` | 用户与认证 | 已请求 | 200/400 |
| PUT | `/admin/files/{fileId}/name` | 管理端·文件与版本 | 未请求 |  |
| PUT | `/admin/model-configs/{id}` | 管理端·模型配置 | 未请求 |  |
| PUT | `/admin/model-configs/{id}/status` | 管理端·模型配置 | 未请求 |  |
| PUT | `/admin/templates/{id}` | 管理端·公共模板 | 未请求 |  |
| PUT | `/admin/templates/{id}/enabled` | 管理端·公共模板 | 未请求 |  |
| PUT | `/admin/users/{userId}/role` | 管理端·用户 | 未请求 |  |
| PUT | `/admin/users/{userId}/status` | 管理端·用户 | 未请求 |  |
| PUT | `/file/{fileId}/name` | 文件管理 | 已请求 | 200/0 |
| PUT | `/file/{fileId}/versions/{versionNo}/ocr/corrections` | OCR 识别与校正 | 已请求 | 200/0 |
