# 海卓大脑后端镜像

在本目录执行 `docker build -t haizhuo-ai-be .`（Java 21）。在原单仓库中从 ag-ui 目录构建。

Demo 配置集中在 src/main/resources/application.properties，随 JAR 打包。容器监听 8087。修改配置后重新构建镜像，再更新 Kubernetes 工作负载。请限制仓库和镜像访问范围，因为配置包含凭据。

数据库为 172.31.1.10:13049/agent_demo。部署前准备应用所需表结构，参考 src/main/resources/db/mysql。技能表由部署方预先创建，脚本为 V6__agent_skill.sql。应用启动会确保登录会话表存在。登录使用 app_user 中的有效账号及 BCrypt 密码哈希。请在首次登录前准备账号。

在 Kubernetes 中为后端提供 8087 端口的 Service。会话和技能数据存储在 MySQL 中。

---

## 多用户改造（2026-11）：用户工作空间 + 技能归属

本次改动涉及 **数据库结构**，部署前必须对目标库（application.properties 里配置的那个库）执行以下脚本，缺一不可：

| 脚本 | 作用 | 适用 |
|---|---|---|
| `V7__skill_owner.sql` | `agentscope_skills` 增加 `owner_user_id`（0=平台公共），唯一键改为 `(owner_user_id, name)` | 已有库 |
| `V8__user_role.sql` | `app_user` 增加 `role`（admin/user），并把 `id=1` 设为管理员 | 已有库 |

新库直接执行 `V6__agent_skill.sql` + `V2__app_user.sql`（已含上述列），不必再跑 V7/V8。

```bash
mysql -h <host> -P <port> -u <user> agent_demo -e "source V7__skill_owner.sql"
mysql -h <host> -P <port> -u <user> agent_demo -e "source V8__user_role.sql"
```

### 运行时行为变化

- **用户工作空间**：每个用户一个目录 `<workspace-root>/<userId>/`，默认 `./data/workspace`（配置项 `chat.workspace-root`）。
  容器部署时把该目录挂到持久卷，否则重建容器会丢目录（当前文件工具关闭，目录仅作隔离骨架）。
- **技能归属**：平台技能对所有登录用户可见；用户私有技能仅本人可见；同名时用户层覆盖平台层。
- **技能管理权限**：上传/删除仅管理员（`app_user.role = 'admin'`）；普通用户只能查看。
- **兼容性**：不再使用框架的 `JdbcAgentSkillRepository`（它依赖 `namespace` 列，本库历史表没有）。
  技能读写改由应用自管的 `SkillStore` 完成，原 `SkillJdbc` 配置已删除。

### 验证清单

1. 启动日志出现 `用户工作空间根目录: .../data/workspace`
2. 两个不同账号分别登录并各发一条消息 → 磁盘出现 `data/workspace/<id>/` 两个目录
3. 管理员上传平台技能（`ownerUserId` 不传）→ 所有账号可见
4. 管理员上传用户技能（`ownerUserId=<用户id>`）→ 只有该用户可见
5. 普通账号调用 `POST /api/skills/file` → 403
