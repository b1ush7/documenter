# 真实 LibreOffice 验收

2026-10-18 在 Windows 上使用 LibreOffice 26.8.1.1、随附 UNO Python 和系统宋体完成固定中文样本的实际渲染。Office 进程读取 DOCX/XLSX 并导出 PDF，PDFBox 再生成页面 PNG；不是仅检查 OOXML 结构，也不使用替代图片伪造结果。

## 环境复现

运行 `scripts/prepare-office-runtime.ps1`。脚本固定官方 26.8.1 x64 MSI，SHA-256 为 `c6298b10cfa1748bcbedad94c62ce771e240e5021bfd6d7a967cd571b04b5312`，同时检查 The Document Foundation 签名。通过 MSI administrative image 提取到独立目录，不修改 WPS 和文件关联。运行时约 2 GB，不提交 Git，也不放入 JAR。首次准备需要网络。

设置 `PREVIEW_OFFICE_BACKEND=libreoffice`、`PREVIEW_OFFICE_EXECUTABLE` 和 `PREVIEW_OFFICE_PYTHON`，具体见部署文档。本机忽略的 `application-service.yaml` 已配置此运行时；共享配置仍默认 `none`，部署时应明确启用。

```powershell
$env:DOCUMENTER_OFFICE_TEST_ENABLED = 'true'
mvn test '-Dtest=NativeOfficeIntegrationTest,CompatibilityVerificationServiceImplTest'
```

完整 HTTP 测试另外需要 `DOCUMENTER_APP_TEST_ENABLED=true`、`DOCUMENTER_LIFECYCLE_DB_TEST_URL`（本机 MariaDB 服务 URL，末尾 `/`）、相应 USER/PASSWORD，以及 `DOCUMENTER_TEST_REDIS_PORT`。只创建随机隔离库，不覆盖业务库。运行 `ApplicationFlowIntegrationTest` 验证真实上传、PNG 预览、兼容性检查及 PDF 导出，读取后版本数保持 1。

## 固定样本与检查

样本生成器为 `OfficeAcceptanceSamples`。输出位于 Git 忽略的 `target/office-acceptance/`：

| 文件 | 实际检查 |
| --- | --- |
| `docx-layout.pdf`，3 页 | 中文字体、图片、目录从标题自动生成、最终 1/3 至 3/3 页码、页眉页脚、横向及纵向合并、拆分后的单元格、多段脚注；逐页查看 PNG 无裁切和乱码 |
| `xlsx-layout.pdf`，1 页 | 两行金额公式重算为 75.00、46.50，合计 121.50，中文打印布局与柱状图；检查图形实际有色像素，避免只有坐标轴和标题 |
| `pdf-rebuilt-layout.pdf`，3 页 | 原生中文 PDF 转可编辑 DOCX 后由 Writer 再次导出；正文保留、实际绘制图片留在原页，无共享资源导致的错页图片 |

原始 Office 文件在转换前后逐字节一致。PNG 分别位于 `docx-pages`、`xlsx-pages`、`rebuilt-pages`。另有折线图、饼图真实 Calc 用例，对应 `LINE-chart.png`、`PIE-chart.png`，均检查系列实际显示并查看图形；带 WEBSERVICE 的 XLSX 被明确拒绝，本机 HTTP 探针收到 0 次请求。

验收发现并修复：图表缺省主题/坐标轴导致柱子不可见；PDF 资源表中存在但页面未绘制的图片被错误插入；DOCX 横向合并时显式首选宽度未相加。合并后拆分的宽度另有自动回归。折线图不额外设置点大小，避免当前 POI 精简 schema 缺少该类型导致保存失败。

## 验收边界

此结果覆盖上述固定样本和本机 LibreOffice，不能推定所有复杂文档均与 Microsoft Word 一致。可编辑 PDF 重建使用阅读行与图片的启发式布局，表格会线性化，页眉页脚可能成为正文，不承诺精确还原。双栏阅读顺序、混合扫描页的真实 OCR 质量、业务模板、复杂脚注跨页和跨平台字体差异仍需对应样本；远程 OCR 按当前范围暂缓。Excel 仅覆盖已有基础公式与图表，不增加复杂公式引擎。

标准后端每次隔离 profile，关闭宏和链接更新，拒绝 XLSX 外部计算；支持超时终止子进程、输入字节/页面像素限制。无运行时返回 503，超时 504，无效或无法加载的文档 422，资源超限 413。首次启动较慢，HTTP 验收为 Office 请求单独预留 150 秒，不改变普通接口的客户端超时。

## 本轮结果

全量回归发现的测试用户名长度及折线图 marker schema 问题修复后，分别重跑受影响测试组；最终 Surefire 报告汇总 835 项，失败 0、错误 0、跳过 0。包含 6 项真实 Office 集成用例、原有 2 项兼容性 Office 用例、7 项完整 HTTP 场景，以及当前 JAR 的 2 项独立进程验收。`mvn -o package` 成功，JAR 内包含 `office/office_convert.py`；JAR 强杀/重启及租约恢复通过。验收结束时无残留随机测试库或 soffice 子进程，`git diff --check` 通过。该数字是本轮全量执行与修复后定向重跑的最终报告汇总，不代表生产环境认证。
