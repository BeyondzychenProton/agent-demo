# Design: 移除 MySQL，改用 workspace SQLite 存储

日期：2026-11-04  
分支：`feature/ag-ui-core`  
状态：已通过对话确认，待实现

## 背景

当前分支用 MySQL + MyBatis-Plus 持久化 SessionLog、会话目录、展示视图与读模型，本地 demo 需要外部数据库才能启动。历史实现曾使用 workspace 下的 SQLite 文件与 AgentScope `WorkspaceSessionLogStore`，但在迁 MySQL 时 Path 构造被 stub 掉，源码未能从 LocalHistory 还原。

目标：彻底去掉 MySQL，恢复本地 workspace 文件型 SQLite 闭环；HTTP/AG-UI 契约不变；不迁移现有 MySQL 数据。

## 架构

权威会话事实仍是 AgentScope `SessionLog`。目录、展示、读模型是可重建的派生索引。

```
chat.workspace/
├── .agentscope-runtime/journal.sqlite3   ← SessionLog（WorkspaceSessionLogStore）
├── thread-catalog.sqlite3                ← 会话目录 + display_* + display_view
├── read-model.sqlite3                    ← rm_* 展示读模型
└── notes/ …                              ← 工具文件（不变）
```

数据流：

1. HTTP 提交 → `ChatSessions` → HarnessAgent 写入 `WorkspaceSessionLogStore`
2. 提交/投影后同步 `ThreadCatalog` / `DisplayViewStore`
3. `ProjectionService` 读 SessionLog，写入 `ReadModelStore`
4. 前端仍走现有 REST/SSE

单进程假设不变。首次启动在空 workspace 建表；不从 MySQL 迁数据。

## 组件

### ChatSessions

- 构造函数只保留 `(Path workspace, Model model)`（测试与启动共用）
- 内部创建 `WorkspaceSessionLogStore(new LocalFilesystem(workspace))`
- `userId` 固定为 `local-user`
- `ThreadCatalog` / `DisplayViewStore` / `ProjectionService` 全部用 workspace Path 构造
- 删除对 `DataSource`、`ThreadCatalogService`、`SessionLogStore` 注入构造的依赖

### ThreadCatalog

- 恢复 `ThreadCatalog(Path workspace)`
- 打开 `workspace/thread-catalog.sqlite3`
- 表：`thread_catalog`、`display_head`、`display_message`、`display_turn`
- MySQL `ON DUPLICATE KEY UPDATE` 改为 SQLite `ON CONFLICT … DO UPDATE`
- 公开行为对齐现有测试：分页游标、`retain`、展示快照水位、重启后标题/时间不变

### DisplayViewStore

- 恢复 `DisplayViewStore(Path workspace)`
- 使用同一 `thread-catalog.sqlite3` 中的 `display_view`
- 水位匹配才命中；旧 seq 不能覆盖新水位；损坏载荷/未知 format 视为未命中

### ReadModelStore / ProjectionService

- 恢复 `ReadModelStore(Path workspace)`，文件为 `workspace/read-model.sqlite3`
- schema 对齐现有 `rm_*`（`rm_head`、`rm_raw`、`rm_item`、`rm_pending`、`rm_change`、`rm_block`、`rm_content`），SQL 改为 SQLite 方言
- `ProjectionService` 只保留 Path 构造；删除 `DataSource` 构造

### 装配与依赖

- `ChatConfiguration`：只注入 workspace + 模型配置；返回 `new ChatSessions(workspacePath, model)`
- `ChatApplication`：去掉 `@MapperScan`
- `pom.xml`：移除 `mybatis-plus-spring-boot4-starter`、`mysql-connector-j`、`spring-security-crypto`（仅用户密码哈希用）；增加 `org.xerial:sqlite-jdbc`（目录/读模型 JDBC；Session 由 harness 自带 sqlite）
- `application.properties`：删除 `spring.datasource.*`、`mybatis-plus.*`、`chat.user-id`
- `.env.example`：删除 `MYSQL_*`、`CHAT_USER_ID`

## 删除清单

- `MySqlSessionLogStore`
- `domain/**`、`mapper/**`、`service/**`（含 User / ThreadCatalog / display / readmodel / session 的 MyBatis 层）
- `config/AuditMetaObjectHandler`
- `src/main/resources/db/mysql/**`
- 其它仅服务 MySQL 持久化的重复包与 bean

## 错误处理

- 建库/建表失败：启动时 `CREATE TABLE IF NOT EXISTS`；失败抛 `IllegalStateException`，不降级内存
- SQL/写入错误：目录、展示、读模型抛运行时异常，不吞掉
- 损坏派生载荷：解码失败视为未命中，由 SessionLog 重算；不改权威日志
- 旧水位覆盖：展示视图拒绝错配 seq；SQLite upsert 保住新水位
- SessionLog：由 harness CAS / `WorkspaceSessionLogStore` 负责；应用不直接读写 `journal.sqlite3`
- 并发：各 store 继续 `synchronized` / 短连接事务；不引入连接池

## 测试与验证

- 启用并修复依赖 `ChatSessions(workspace, model)` / `ThreadCatalog(workspace)` / `DisplayViewStore(workspace)` 的测试（含去掉「待改用 MySQL」的 `@Disabled`）
- `mvn -pl ag-ui test` 重点：`ThreadCatalogTest`、`DisplayViewStoreTest`、`HitlIntegrationTest`、`ProjectionServiceTest`、`AguiQueuedRunTest`
- 本地启动不连 MySQL；workspace 出现上述 SQLite 文件
- 发消息 → 重启 → 列表与历史仍在
- 编译产物无 mysql / mybatis 依赖

## 非目标

- 不迁移现有 MySQL 数据
- 不引入多租户用户表 / 登录
- 不改变前端协议与 AG-UI 事件形状
- 不做多进程共享 SQLite 写入
