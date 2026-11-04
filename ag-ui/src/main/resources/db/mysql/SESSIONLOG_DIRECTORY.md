# MySQL 表说明

手动迁移顺序：V2 → V3 → V4 → V5（`auth_session`）。应用启动也会确保 `auth_session` 存在并写入种子用户。

会话目录的标题、创建时间和更新时间现在从 SessionLog 与 Inbox 派生。agent_session 仍供 SessionLogStore.list() 枚举用户会话；agent_session_object 保存日志对象；display_* 和 rm_* 是可重建的展示读索引。

新环境不再创建 thread_catalog。旧环境中的同名表不会被程序读取，也不会自动删除。确认新版本的会话列表、标题和重启恢复正常后，可由数据库管理员执行：

    DROP TABLE IF EXISTS thread_catalog;

用户表 app_user 由 V2 维护；登录会话表 auth_session 由 V5 维护（应用启动也会 `CREATE TABLE IF NOT EXISTS`）。浏览器登录后通过 SecurityContext 的 userId 绑定 agent_session / 读模型命名空间；种子用户默认手机号见 `chat.auth.seed-phone`。展示兼容接口继续使用 display_*，当前页面继续使用 rm_*。
