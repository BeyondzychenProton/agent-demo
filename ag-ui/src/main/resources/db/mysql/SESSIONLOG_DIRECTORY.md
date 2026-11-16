# MySQL 表说明

手动迁移顺序：V2 → V3 → V4 → V5（`auth_session`）。应用启动也会确保 `auth_session` 存在并写入种子用户。

会话事实（事件、checkpoint、inbox）写在每个用户的工作区里：`chat.workspace/<userId>/.agentscope-runtime/`。这是 Harness 默认的 `WorkspaceSessionLogStore`，隔离范围是 `IsolationScope.USER`。应用通过 SessionLog API 读取，不解析里面的 SQLite 或提交文件。

`agent_session` 只登记会话归属；`agent_session_object` 只保存会话标题。它们不再保存日志对象。`display_*` 和 `rm_*` 仍是可重建的展示读索引。已有 MySQL 日志不会自动迁进工作区。

新环境不再创建 thread_catalog。旧环境中的同名表不会被程序读取，也不会自动删除。确认新版本的会话列表、标题和重启恢复正常后，可由数据库管理员执行：

    DROP TABLE IF EXISTS thread_catalog;

用户表 app_user 由 V2 维护；登录会话表 auth_session 由 V5 维护（应用启动也会 `CREATE TABLE IF NOT EXISTS`）。浏览器登录后通过 SecurityContext 的 userId 绑定 agent_session / 读模型命名空间。展示兼容接口继续使用 display_*，当前页面继续使用 rm_*。
