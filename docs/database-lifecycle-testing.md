# 数据库升级、版本事务与配套备份恢复验收

`DatabaseLifecycleIntegrationTest` 需要显式启用，只连接指定的本机 MariaDB。它新建随机 `documenter_it_<32位UUID>` 数据库，并在测试结束时只删除本轮成功创建的库，不会升级、清空或备份现有 `documenter` 业务库。临时文件由 JUnit 隔离管理。

## 已验证的范围

2026-10-17 在本机 MariaDB 13.0.2 上通过 4 项测试：

1. 从空库执行 V1，写入旧格式文档数据，升级至 V5 后写入旧模板，再升级到 V9；校验 V2 的版本格式回填、V6 的 BASELINE 历史、V7/V8/V9 表与字段、全部迁移校验和，以及再次启动不重复迁移。
2. 使用真实 MyBatis-Plus Mapper、Spring 事务代理和 `DocumentVersionServiceImpl`：写版本失败后回滚版本号分配；同一任务尝试重放不重复新增版本；跨用户读取被拒绝；恢复历史版本生成新版本且内容正确。
3. 16 个并发请求编辑同一预期版本，只有一个成功，其余返回业务码 409；最终版本号和版本记录数量一致，没有部分写入。实测发现 MariaDB 1020 并发写冲突，已在版本分配处与死锁/锁等待冲突统一转为 409，并由事务边界回滚；连接丢失等无关数据库故障继续按原异常处理。
4. 在测试静止状态下用实际 `mariadb-dump` 备份整个测试数据库，同时归档对应文件存储；将 SQL 恢复到另一个随机库，文件解压到独立目录。逐版本核对存储内容 SHA-256、最新版本和记录数，并验证恢复后的 Flyway 校验和与再次迁移结果。备份只含合成测试数据，不验证 Office 版式。

## 执行

需要本机测试服务器的建库/删库权限；备份用例另需 MariaDB 客户端目录。密码通过进程环境变量注入，不写入源码或命令参数。示例：

```powershell
$env:DOCUMENTER_LIFECYCLE_DB_TEST_ENABLED = 'true'
$env:DOCUMENTER_LIFECYCLE_DB_TEST_URL = 'jdbc:mariadb://127.0.0.1:3306/'
$env:DOCUMENTER_LIFECYCLE_DB_TEST_USER = 'documenter_test'
$secret = Read-Host '专用测试账号密码' -AsSecureString
$env:DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$env:DOCUMENTER_TEST_MARIADB_BIN = 'D:/mariadb/bin'
mvn -o test '-Dtest=DatabaseLifecycleIntegrationTest'
```

URL 仅接受本机服务器级地址，不接受业务库名。未设置总开关时整个类跳过；未设置客户端目录时只有备份用例跳过。设置后连接或工具失败会让测试失败，不会当成成功。客户端使用 `--no-defaults` 避免读取其他连接配置；数据库密码只传给客户端的 `MYSQL_PWD` 环境变量。

受限 Windows 环境若测试 JVM 无法派生，可将项目指定版本的 Mockito agent 加到当前进程 `MAVEN_OPTS`，用 `-DforkCount=0` 运行；结束后恢复原环境变量。常规环境仍使用默认分叉测试配置。

## 仍需验收

本轮证明的是本机合成数据下的完整迁移、文档版本事务/并发，以及配套逻辑备份恢复。后续已补主要业务模块实库回滚、写盘/提交边界独立 JVM 强杀及正式回收，见 [business-transaction-testing.md](business-transaction-testing.md)；大文件与多实例结果另见各专项文档。已有业务数据恢复副本、数据库服务器故障、共享卷、Redis 持久化恢复及正式部署演示仍需分别验收。
