# Documenter

通过受控工具处理 DOCX、XLSX、PDF 与图片。后端采用 Java 21、Spring Boot、MariaDB、Redis 和 Apache POI；当前仓库尚无前端工程。

已具备账号、文件及版本管理、DOCX 结构块及公共模板变量生成与编辑、XLSX 编辑及公式、PDF 原生文本提取、图片基础处理，以及同步 AI 回复与确认流程。图片只做本地格式/尺寸调整、裁剪、直角旋转、亮度和单行水印，不接入图片生成或智能修图模型；PDF/图片 OCR 继续规划。真实短信、文本模型效果、OCR 和完整生产环境仍待接入或验收。

## 本地开始

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

离线依赖已缓存时可用 `mvn -o test`。Redis 和 Office 集成测试有独立前置条件，跳过不等于验收通过。
