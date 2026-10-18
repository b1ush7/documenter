# 正式文件回收

正式存储回收与临时文件清理独立。升级需执行 V8 迁移；默认关闭实际回收，默认仅预演。

## 保留规则

- 未回收文件：当前内容、原始上传及全部历史版本长期保留，不按版本年龄修剪。
- 软删除文件：默认保留 30 天。删除时间缺失的记录不回收；有关联 PENDING/RUNNING 任务的文件暂缓回收。同一用户有运行中 AI 回复或待确认计划时，保守暂缓该用户全部软删除文件，避免删除计划中跨文件插图所需的源图片；确认或放弃计划后即可继续回收。
- 过期软删除文件先写入 `file_asset.storage_purged_time` 墓碑，再释放其当前与历史版本的物理引用。元数据、版本链、任务及审计记录保留；用户及管理员通过文件 ID 访问该文件均返回 404。不能撤销墓碑；需要恢复时使用成套数据库和存储备份。
- 模板当前文件和全部模板历史长期保留，包含已删除模板的历史。版本表有索引而文件元数据缺失时也保守保留。
- 同一个存储标识只要仍被任意未回收文件、版本或模板引用，就不删除。
- 无保护引用的文件至少经过 24 小时孤儿宽限期。宽限期必须长于部署中的最长上传、事务和处理时间，配置下限为 1 小时。

实际删除前重新检查引用、路径、文件身份、大小和修改时间。数据库查询失败或返回异常时停止当前轮次；不会按“无引用”处理。物理删除失败会记录 FAILED 并在以后重试；墓碑不回滚，避免重新开放已部分回收的文件。

写入使用新的 UUID 文件并持有排他文件锁，全部写完后更新时间，才将 key 返回调用方用于提交元数据。正在写的文件即使超过宽限期也会跳过；写入失败会清理部分内容，崩溃遗留的未引用文件可在宽限期后回收。文件内容发布后不可原地覆盖。

## 配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `STORAGE_RECYCLE_ENABLED` | `false` | 允许实际回收并启动定时轮次 |
| `STORAGE_RECYCLE_DRY_RUN` | `true` | true 时定时任务仅预演，手工实际回收也被拒绝 |
| `STORAGE_RECYCLE_INTERVAL` | `24h` | 定时执行间隔 |
| `STORAGE_DELETED_RETENTION` | `720h` | 软删除文件的内容保留期 |
| `STORAGE_ORPHAN_GRACE` | `24h` | 未引用文件的修改时间宽限 |
| `STORAGE_RECYCLE_BATCH_SIZE` | `100` | 每轮最多标记的文件数，以及最多尝试删除的物理文件数；范围 1～1000 |

正式目录必须没有符号链接，正式和临时目录的真实路径不得互相包含。只扫描服务端生成的 `yyyy/MM/dd/UUID.extension` 布局，不跟随链接，不删除目录或非受管命名文件。自建目录、未知命名文件需人工核对，不会自动删除。

同一正式目录使用 `.recycle.lock` 文件锁排除并发回收；共享卷必须支持跨进程文件锁。不同存储根不共享扫描结果；全部写入实例必须先升级到带写入文件锁保护的版本。开启真实回收前，先完成备份及测试库验收。建议第一次在暂停写入的维护窗口执行，之后再开启定时回收。

## 管理员入口

`POST /admin/storage/recycle?dryRun=true`

需要 ADMIN，省略 `dryRun` 也只预演。预演不写业务墓碑、不删除内容，可以在两个配置开关的默认状态下运行；用于互斥的锁文件仍会创建。响应使用统一 `ApiResponse`：

```json
{
  "code": 0,
  "data": {
    "dryRun": true,
    "expiredFiles": 1,
    "scannedFiles": 16,
    "deletedBlobs": 0,
    "deletedBytes": 0,
    "truncated": false,
    "entries": [{"storageKey": "2026/10/01/00000000-0000-0000-0000-000000000001.docx", "sizeBytes": 100, "outcome": "WOULD_DELETE"}]
  }
}
```

`expiredFiles` 为本批符合回收条件的业务文件数，`entries` 为本批满足物理回收条件的文件，数量可能不同；共享引用和孤儿宽限期都会使物理删除延后。`truncated=true` 表示批量上限被触及，需继续运行；false 也不能代替未来到期文件的定期扫描。`outcome` 为 WOULD_DELETE / DELETED / FAILED。

确认清单和保留期后设置 `STORAGE_RECYCLE_ENABLED=true`、`STORAGE_RECYCLE_DRY_RUN=false`。定时器启动即执行首轮，之后按配置间隔执行；也可用 `POST /admin/storage/recycle?dryRun=false` 触发一轮。禁止普通用户调用。手工操作记录 `ADMIN_STORAGE_RECYCLE` 审计，定时轮次及实际删除 key 记录服务端日志。数据库故障可能使一轮部分完成，修复后重复执行即可；成功删除的文件不会再次计入。

## 验证

离线单元回归：

```powershell
mvn -o test '-Dtest=StorageRecycleServiceTest,StorageRecycleMapperTest,PurgedFileAccessTest,AdminResourceServiceImplTest,SecurityFilterChainTest'
```

真实 MariaDB 集成使用显式环境变量，只在该连接上创建临时表并运行 V8 的 DDL，不清库、不修改正式表。请仍使用专用测试库及专用账号，需具备创建临时表权限：

```powershell
$env:DOCUMENTER_RECYCLE_DB_TEST_ENABLED = 'true'
$env:DOCUMENTER_RECYCLE_DB_TEST_URL = 'jdbc:mariadb://localhost:3306/documenter_test'
$env:DOCUMENTER_RECYCLE_DB_TEST_USER = 'documenter_test'
# 密码通过本地安全配置注入 DOCUMENTER_RECYCLE_DB_TEST_PASSWORD，不写入命令历史。
mvn -o test '-Dtest=StorageRecycleMariaDbIntegrationTest'
```

2026-10-17 已在本地 MariaDB 13.0.2 执行并通过此测试：连接级临时表中的 V8 DDL、真实 MyBatis 查询/更新，以及真实隔离磁盘上的预演、过期文件删除、共享引用保护、跨文件待确认图片保护和重复执行。没有修改现有业务表或正式存储目录，凭据仅用于进程环境变量。

未启用时此集成测试仍会跳过，不代表当前环境验收通过。完整 Flyway 升级与数据库/文件配套恢复已在随机隔离库验证，见 [数据库生命周期验收](database-lifecycle-testing.md)；已有业务数据副本、正式存储恢复、共享卷文件锁和大目录扫描仍需单独验收。
