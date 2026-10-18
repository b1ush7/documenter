# Documenter

通过受控工具处理 DOCX、XLSX、PDF 与图片。后端采用 Java 21、Spring Boot、MariaDB、Redis 和 Apache POI；当前仓库尚无前端工程。

已具备账号、文件及版本管理、DOCX 结构块、表格行列/行高/列宽、重复标题及受限矩形合并/拆分、模板变量、页眉页脚分节链接、正文脚注、可更新 TOC 域及公共模板生成与编辑、XLSX 编辑、公式、新表格和基础图表、PDF 原生文本提取与可编辑 DOCX 重建、可选云端 OCR 及扫描 PDF 文字重建、图片基础处理、真实 `CONVERT`/`EXPORT` 异步任务，以及同步/SSE AI 回复、来源提示与确认流程。图片只做本地格式/尺寸调整、裁剪、直角旋转、亮度和单行水印，不接入图片生成或智能修图模型。云端 OCR 默认关闭，复用管理端 `scope=OCR` 的默认模型、加密密钥和 OpenAI 兼容视觉接口；真实供应商、中文识别质量、文本模型效果、短信和完整生产环境仍待验收。

## 本地开始

当前阶段验证码和 OCR 不接入远程服务：保持 `VERIFICATION_MOCK_ENABLED=true`、`OCR_ENABLED=false`。验证码只走本地 Mock，云 OCR 不发送页面；PDF 原生文本提取及本地图片预处理实现保留。真实短信/OCR 供应商选择与质量验收暂缓，后续明确启用时再配置。

Excel 保留已有基础编辑、支持的公式与基础图表；复杂公式引擎扩展不在当前交付范围，动态数组等不支持能力继续返回明确提示。

1. 准备 JDK 21、Maven、支持迁移脚本排序规则的 MariaDB 和 Redis。
2. 按 [.env.example](.env.example) 设置进程环境变量；Spring Boot **不会自动读取 .env**。
3. 创建专用空数据库，运行测试并启动应用：

```powershell
mvn test
mvn spring-boot:run
```

默认端口 8080，默认启用 dev 配置。数据库迁移由 Flyway 在启动时执行；Redis 是登录、验证码、上传限流与 AI 日额度的运行依赖。

具体数据库准备、环境变量注入、构建运行、Office 运行时、备份与演示步骤见 [部署说明](docs/deployment.md)。

## 项目文档

- [开发 TODO 与真实验收边界](TODO.md)
- [HTTP API](API.md)
- [前端页面接口清单](docs/frontend-api-by-page.md)
- [前端规划](docs/frontend-plan.md)
- [安全与专用 Redis 集成测试](docs/security-testing.md)
- [固定验收样本与人工检查步骤](docs/acceptance-samples.md)
- [真实 LibreOffice 排版、预览与导出验收](docs/office-rendering-testing.md)
- [OCR 图片预处理](docs/ocr-preprocessing.md)
- [正式文件回收与保留规则](docs/storage-recycling.md)
- [数据库升级、版本事务与备份恢复验收](docs/database-lifecycle-testing.md)
- [完整 HTTP 流程、重启与受控故障验收](docs/application-flow-testing.md)
- [大文件、并发传输与双应用实例验收](docs/load-and-multi-instance-testing.md)
- [可选执行租约、多实例失联恢复与写入隔离](docs/execution-leases.md)

离线依赖已缓存时可用 `mvn -o test`。Redis 和 Office 集成测试有独立前置条件，跳过不等于验收通过。
