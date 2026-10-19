# 阿里云 OSS 文件存储

正式上传文件、文档版本、模板和转换产物通过 `FileStorage` 统一写入 OSS。数据库仍保存相对 `storageKey`，如 `2026/10/18/uuid.docx`，实际对象名为 `${OSS_PREFIX}/${storageKey}`。下载仍走现有鉴权接口，由后端读取 OSS 流，不返回公开地址。

## 配置

创建私有 Bucket。为应用使用的 RAM 用户授予该 Bucket 下指定前缀的 `oss:PutObject`、`oss:GetObject`、`oss:DeleteObject` 权限。无需创建/删除 Bucket 或列举整个 Bucket 的权限；文件存在检查使用对象元数据读取权限。不要设置按天删除整个前缀的生命周期规则，活跃文件和历史版本仍被业务引用。

SDK 采用阿里云官方 Java SDK V2，参考 [官方接入说明](https://www.alibabacloud.com/help/en/oss/user-guide/oss-sdk-quick-start)。在部署平台或进程环境中设置以下变量，地域和 Endpoint 必须与 Bucket 一致：

```bash
export STORAGE_TYPE=oss
export OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
export OSS_REGION=cn-hangzhou
export OSS_BUCKET=你的私有Bucket名称
export OSS_PREFIX=documenter
# 在服务器的环境变量管理中注入，勿把真实密钥写入仓库。
export OSS_ACCESS_KEY_ID='替换为RAM用户AccessKeyId'
export OSS_ACCESS_KEY_SECRET='替换为RAM用户AccessKeySecret'

mkdir -p "$HOME/documenter/tmp"
export STORAGE_LOCAL_TEMP="$HOME/documenter/tmp"

java -Duser.timezone=Asia/Shanghai \
  -jar /usr/documenter-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=service
```

不要把 Bucket 名拼到 Endpoint 上；示例地域需替换为实际地域。可使用对应地域可达的 OSS HTTPS 内网 Endpoint。`OSS_PREFIX` 允许多级目录，只包含字母、数字、下划线和短横线；首次写入后保持 Bucket 和前缀稳定，修改会改变已有 `storageKey` 指向的位置。

可选 `OSS_SECURITY_TOKEN` 适用于 STS 临时凭据，与对应的临时 AccessKey 一起配置。本实现使用静态凭据提供器，不自动续期；凭据过期前需要更新环境并重启。未使用 STS 时留空。

本仓库基础配置默认 `local`，本机 Git 忽略的 `application-service.yaml` 已改为默认 `oss`；该文件按项目现有约定随 JAR 打包。其他源码检出如果没有这份文件，需要自行准备 service 配置或显式提供完整数据源配置与 `STORAGE_TYPE=oss`。`.env.example` 只是清单，Spring Boot 不会自动加载 `.env`。

## 本地临时目录仍然需要

OSS 模式不会创建 `STORAGE_LOCAL_ROOT` 正式存储目录。上传先流式写入本地临时副本，再交给 SDK 上传，避免重试重复读取摘要流；成功和失败都会尝试清理副本。预览、OCR 缓存、Office 转换和 servlet multipart 也仍需可写的本地磁盘。service 配置的业务临时目录默认 `${java.io.tmpdir}/documenter`，可通过 `STORAGE_LOCAL_TEMP` 覆盖；操作系统和 JVM 的默认临时目录也要可写，并预留磁盘空间。

本地临时清理任务在 OSS 模式继续运行。上传副本通常立即删除，异常退出遗留文件按临时保留策略清理。DOCX/XLSX 页面预览和 PDF 导出仍需配置服务器上的 LibreOffice/UNO，与 OSS 无关。

## 回收、历史版本和旧文件

OSS 模式下，现有本地正式存储永久回收任务自动跳过；管理端 `/admin/storage/recycle` 返回业务码 409，明确提示暂不支持 OSS 永久回收。软删除与版本管理保持原有语义；事务回滚和失败产物的精确对象删除仍通过 OSS 执行。OSS 请求结果不确定或进程强杀时可能遗留无引用对象，目前不会自动扫描清理这些对象。

不要直接将本地回收逻辑用于 Bucket，也不要配置忽略业务引用的自动删除规则。需要备份数据库、OSS 内容与版本历史（可结合 Bucket 版本控制），并稳定保存原有模型加密密钥和 JWT 密钥。

当前服务器尚无业务数据，可直接启用 OSS。已有本地文件的实例需要停写后把正式存储下所有对象按原相对路径复制到目标 OSS 前缀，再核对数据库引用、数量与 SHA-256；本次未提供自动搬迁工具。只切换配置不会搬迁文件。

## 验证

缺少 OSS 必填配置会在启动时明确报出变量名；初始化不连接云端，启动成功不代表 Bucket/权限验证通过。真实验收需要用现有 HTTP 接口登录、上传文件、下载并对比 SHA-256，再执行一次编辑/版本下载和模板处理，并核对 Bucket 中对象前缀及本地临时清理。

单元测试覆盖 SDK 调用参数、流读取及摘要、失败清理、错误映射、对象名校验、临时回收和 Spring 模式装配。它们不使用真实 Bucket，不代表云端上传下载已经验收。
