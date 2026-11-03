# MySQL 表说明

会话目录的标题、创建时间和更新时间现在从 SessionLog 与 Inbox 派生。agent_session 仍供 SessionLogStore.list() 枚举用户会话；agent_session_object 保存日志对象；display_* 和 rm_* 是可重建的展示读索引。

新环境不再创建 thread_catalog。旧环境中的同名表不会被程序读取，也不会自动删除。确认新版本的会话列表、标题和重启恢复正常后，可由数据库管理员执行：

    DROP TABLE IF EXISTS thread_catalog;

账号注册链路在当前应用没有入口，空的 app_user 表及其 MyBatis-Plus 类已移除；用户 ID 仍用于会话命名空间。展示兼容接口继续使用 display_*，当前页面继续使用 rm_*。
