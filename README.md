# Documenter

通过受控工具处理 DOCX、XLSX、PDF 与图片。后端采用 Java 21、Spring Boot、MariaDB、Redis 和 Apache POI。

## 本地开始

验证码默认走本地 Mock，保持 `VERIFICATION_MOCK_ENABLED=true`。OCR 默认关闭；已接入阿里云 `RecognizeAllText`，开通高精版后按 [阿里云 OCR 部署说明](docs/aliyun-ocr.md) 配置 `OCR_PROVIDER=aliyun`、`ALIYUN_OCR_TYPE=Advanced`、RAM 凭据及 `OCR_ENABLED=true`。支持行文字、原图坐标、表格结构和扫描 PDF 逐页识别，实际识别质量仍需样本验收。

1. 准备 JDK 21、Maven、支持迁移脚本排序规则的 MariaDB 和 Redis。
2. 按 [.env.example](.env.example) 设置进程环境变量；Spring Boot **不会自动读取 .env**。
3. 创建专用空数据库，运行测试并启动应用：

```powershell
mvn test
mvn spring-boot:run
```

默认端口 8080，默认启用 dev 配置。数据库迁移由 Flyway 在启动时执行；Redis 是登录、验证码、上传限流与 AI 日额度的运行依赖。

正式文件可通过 `STORAGE_TYPE=oss` 切换到阿里云 OSS。预览和转换仍使用本地临时目录。

离线依赖已缓存时可用 `mvn -o test`。Redis 和 Office 集成测试有独立前置条件。
