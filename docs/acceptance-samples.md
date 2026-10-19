# 固定验收样本

当前基础样本集为 basic-v1，文本、公式、数值、图片尺寸和修改目标固定。通过实际服务生成编辑产物并检查，再将输入与输出一起留给人工验收；不增加生产 HTTP 接口。

## 生成与查看

在项目根目录执行：

```powershell
mvn -o '-Dtest=AcceptanceSamplesTest' test
if ($LASTEXITCODE -ne 0) { throw '样本回归失败，不能判为验收通过' }
$sampleDirectory = Join-Path (Get-Location) 'target/acceptance-samples'
$manifest = Get-Content (Join-Path $sampleDirectory 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $manifest.allAutomaticChecksPassed) { throw '存在未完成的自动检查' }
foreach ($artifact in $manifest.artifacts) {
    $artifactPath = Join-Path $sampleDirectory $artifact.file
    if ((Get-Item $artifactPath).Length -ne $artifact.sizeBytes) { throw "大小不一致：$($artifact.file)" }
    if ((Get-FileHash $artifactPath -Algorithm SHA256).Hash -ne $artifact.sha256) { throw "摘要不一致：$($artifact.file)" }
}
$manifest.automaticChecks | Format-List
Get-ChildItem $sampleDirectory -File | Select-Object Name, Length
```

完整 `mvn -o test` 也运行这 6 项样本测试；初次获取依赖时按部署文档去掉 `-o`。输出都在被 Git 忽略的 target 下，不会进入业务正式存储或 JAR。测试源代码可随仓库保存，产物可按需生成、导出供验收。

manifest.json 记录样本版本、生成时间、各项自动检查结果、文件大小、SHA-256、预期内容和未执行项。每次运行会覆盖同名产物；归档人工验收时保存整份目录及清单，不能把后续运行的文件混入旧验收。仅使用本次 manifest 列出的文件。生成前先移除旧清单，发生测试失败时不能因看到旧文件就认为本次已通过。

“固定”指语义和检查目标固定，不保证不同操作系统或不同运行时生成的二进制完全一致。OOXML/PDF 元数据和字体栅格化可能改变文件摘要；SHA-256 用于确认本次被查看或上传的文件未被替换，不是跨环境排版正确性的证明。

## 样本与明确预期

| 文件 | 自动检查与人工检查目标 |
| --- | --- |
| docx-input.docx / docx-edited.docx | 中英标题、正文、两行两列表格、嵌入 PNG、不同格式的 runs、页眉页脚。只把“正文：保留表格和图片。”改为“正文：已修改，表格和图片保持不变。”；POI 重开后核对表格内容、图片原字节、粗体、颜色、页眉页脚保持不变 |
| native.pdf / native-preview.png | 一页原生文本，必须提取到 `Native PDF sample` 和 `Amount: 123.45`，类型 TEXT；预览由实际 PDF 服务按 72 DPI 输出，尺寸与 PNG 解码结果一致 |
| scanned.pdf / scanned-preview.png | 一页图片型 PDF，类型 SCANNED，原生文本为空；图片显示 OCR SAMPLE、Invoice 2026-001、Total: 123.45，不能将扫描页分类当作已完成 OCR |
| mixed.pdf | 两页：第一页 TEXT、第二页 SCANNED；整份文档类型 MIXED，第二页原生文本仍为空 |
| ocr-input.png / ocr-expected.txt | 640×240 的英文与数字 OCR 输入及人工对照文字；预期文本不是识别产物，不执行 OCR，也不代表中文 OCR 已验收 |
| formulas-input.xlsx / formulas-edited.xlsx | 两个工作表“数据”“参照 表”。即时计算 数据!D2:D7 为 60、2、yes、20、14、#DIV/0!；修改 B2 从 10 为 15 后写回新版本，重开检查 D2 缓存为 65、查找与跨表公式仍有效、金额格式和 F2 日期 2026-10-17 保留 |
| image-input.png / image-local-text.png | 320×200，左侧红/蓝色块和透明度标记；在 (180,30) 附近添加黑色 A。遍历全部像素，变化必须落在 x180～219、y30～69 的区域内；其余像素及透明角保持原值 |
| image-cropped.png | 从原图 (20,20) 裁剪 80×60 的红色区域，全部输出像素逐一与源区域对照。它与文字修改样例分别来自原图，不是一条连续编辑链 |
| image-converted.jpg | 原图转 320×200 JPEG，无透明通道，透明角合成白色；JPEG 有损压缩不要求逐像素等于 PNG |

## 验收步骤与记录

1. 确认 Maven 成功，并核对清单摘要；记录 Git 提交或工作区版本、JDK/操作系统、样本版本及生成时间。
2. 先在 Word 或实际部署的 LibreOffice 中分别打开 DOCX 输入、输出，检查正文唯一预期修改、标题与文字格式、表格、图片、页眉页脚及分页。记录软件版本、中文字体和截图；不要只凭 POI 能重开就判定版式通过。
3. 使用 PDF 阅读器查看原生、扫描及混合 PDF，逐页对照预览，检查文字完整性、缩放清晰度和阅读顺序。该基础集不覆盖双栏、复杂表格或中文 PDF 字体，还需要补充这些场景。
4. 在实际办公软件中打开两个 XLSX，对照 D2:D7 的值与错误、金额格式和日期；输入缓存和编辑后的缓存分别核对，再触发重算检查值是否一致。复杂公式、动态数组及 1904 日期系统不在本样本验收范围内。
5. 查看图片输入、局部文字结果、裁剪和 JPEG 转换；必要时使用支持 alpha 的查看器确认透明像素。仅验收本地基础处理；图片生成、模型擦除、对象替换和背景编辑已移出项目范围。
6. 接入 OCR 后，再将 ocr-input.png 和扫描页送入真实引擎，与 ocr-expected.txt 对照文字、行序、坐标和置信度；另补中文、旋转、模糊、表格和复杂扫描样本。当前此步骤未执行。
7. 在实际运行的后端上传同一批源文件，用自己的账号按明确版本调用现有读取、编辑、确认、预览和下载接口；比较下载文件及结果，完成数据库、Redis 和鉴权端到端验收。当前本地样本回归不执行 HTTP 和真实数据库流程，不能替代该步骤。

逐项记录 `通过 / 失败 / 未执行` 及证据文件。未执行项继续保留在 TODO，不用自动检查的 PASSED 代替人工版式、OCR 或真实部署验收。

## 已验证的边界

样本回归调用真实 DocxDocumentServiceImpl、PdfDocumentServiceImpl、XlsxDocumentServiceImpl、ImageDocumentServiceImpl 和本地存储，并校验保存产物的长度与摘要。来源版本和新增版本的数据库边界使用替身；没有连接真实数据库、Redis、模型或 OCR，也没有调用 Word/LibreOffice。

manifest 的 allAutomaticChecksPassed 只描述上述 6 项服务级回归；notExecuted 明确列出 OCR、中文 OCR、Office 版式和数据库/HTTP 流程。API 权限、队列、冲突、超时等继续由其他回归测试及真实环境验收覆盖。

2026-10-18 新增独立的 [真实 LibreOffice 验收](office-rendering-testing.md)，覆盖中文目录、最终页码、合并表格、多段脚注、基础公式/柱状图及原生 PDF 重建，并通过真实 HTTP 预览和导出。它使用另一组固定样本，不改变上述 manifest 的原有统计语义。
