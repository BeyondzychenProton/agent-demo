# 技能管理设计与执行计划

管理端维护技能正文、参考资料、启用状态、可见范围，以及与会话智能体的绑定。运行时仍用当前仓库的 `JdbcAgentSkillRepository` 和 `HarnessAgent`。管理端是唯一写入方，仓库只读。聊天每一轮用整份 `SkillFilter` 换成「绑定 ∩ 启用 ∩ 当前用户可见」。

本文是实现依据。范围是这三件事。会话里临时加技能、场景包、按会话拆缓存、MCP 连接的发现和鉴权，不在本文里。

依赖版本：`agentscope-extensions-jdbc 2.1.0-BETA1`。这个 JAR 的仓库构造器是 `(DataSource, dialect)` 和 `(DataSource, dialect, writeable)`，没有 namespace 参数，技能表也不读 `namespace` 列。

## 1. 已经存在的代码

| 事实 | 位置 | 本方案怎么用 |
| --- | --- | --- |
| 框架表 `agentscope_skills`、`agentscope_skill_resources` 已建，列名固定，没有 `namespace`，没有 `del_flag` | `ag-ui/src/main/resources/db/mysql/V6__agent_skill.sql` | 正文和参考资料继续放这两张表。补业务列，不另建一套框架列 |
| 启动时的仓库默认可写 | `ChatConfiguration#skillRepository` | 改为 `new JdbcAgentSkillRepository(dataSource, dialect, false)` |
| `getSource()` 由技能表名拼出，当前值是 `jdbc_agentscope_skills` | `JdbcAgentSkillRepository#getSource` | 全表继续写这个值。它参与框架内部 skillId，不表示创建人，上线后不改 |
| 上传接口直接 `repository.save` / `delete` | `SkillCatalogServiceImpl`、`POST/DELETE /api/skills/*` | 写入口停用。解析留在 `SkillDocuments`，由管理端导入调用 |
| 覆盖保存会先删技能行再插入 | 同上 JAR 的 save 实现 | 管理端用自己的 SQL 更新，避免业务列被删行丢掉 |
| 只读时 `save`、`delete` 返回 `false` 并打日志，不抛异常 | 同上 JAR | 验收看 `isWriteable()==false`，以及调用后行数不变 |
| 聊天只有一个 `HarnessAgent`，`agentId` 为 `session-chat`，已 `disableDefaultWorkspaceSkills()` | `ChatSessions` | 保持这个标识。管理端展示名用「海卓大脑办公助手」 |
| 智能体在启动时建好，绑定变化不重建 | `ChatSessions` 构造函数 | 装配期放 `SkillFilter.none()`。绑定、启停、可见范围都在下一轮上下文里重算 |
| `HarnessSkillMiddleware.effectiveFilter` 是 `builderFilter.overlay(当轮过滤器)` | harness `2.1.0-BETA1` | `SkillFilter.only` 和 `none` 会整份替换装配期过滤器。当轮过滤器缺失时，保留装配期那份 |
| `applySkillFilter` 与 `SkillPromptBuilder` 用 `AgentSkill.getName()` 做精确匹配 | 同上 | 白名单只放技能名。模型看到的 skill-id 仍是 `名称_jdbc_agentscope_skills` |
| 每轮上下文在 `ChatSessions#context` 构造，目前只有 `userId` 和 `sessionId` | 同文件 | 在这里放入当轮 `SkillFilter`。聊天、AG-UI 提交、审批恢复都走 `session()` |
| 组织部门、成员是真实表，`status char(1)`：`0` 启用，`1` 停用 | `app_directory_department`、`app_directory_user` | 技能状态用同一套取值。本部门比较 `department_id` |
| 登录账号 | `app_user.id`，满天星用户是 `mantianxing_user_id` | 操作人和「仅本人」用 `app_user.id`。部门从通讯录用户查 |
| 管理端页面仍是浏览器演示数据 | `frontend/src/components/CapabilityManagement.tsx` | Skills 页和 Agent 页的绑定改接真实接口 |

原型里的智能体标识 `office-assistant` 只存在于前端假数据。运行中的会话归属键是 `session-chat`。展示名用「海卓大脑办公助手」，绑定键用 `session-chat`。

## 2. 职责

```text
管理端写 agentscope_skills / agentscope_skill_resources / ai_agent_skill
        │
        ▼
JdbcAgentSkillRepository（writeable=false）
        │
        ▼
HarnessAgent 装配：SkillFilter.none()
disableDefaultWorkspaceSkills() 保持开启
        │
        ▼
ChatSessions.context()
SkillFilter.only(绑定 ∩ 启用 ∩ 当前用户可见)
名单为空时用 SkillFilter.none()
        │
        ▼
清单只有 name、description
命中后模型调用 load_skill_through_path
```

| 谁 | 做什么 |
| --- | --- |
| 管理服务 | 维护技能正文、参考资料、可见范围、启用状态，以及与 `session-chat` 的绑定 |
| 技能仓库 | 按技能名读取正文和参考资料 |
| 装配 | 挂上只读仓库，装配期过滤器为 `none()`，并保持工作区技能关闭 |
| 每一轮 | 用「绑定 ∩ 启用 ∩ 当前用户可见」整份替换过滤器 |

装配期使用 `none()`，是因为这个进程里的智能体实例会被所有用户复用，而且绑定变化不会重建实例。可见范围跟人走，不能写进这个实例。`overlay(null)` 会留下装配期过滤器；装配期若是「绑定 ∩ 启用」，某次调用漏放当轮过滤器时，其他用户会看到这份名单。`none()` 让漏放变成空清单。

运行期过滤器是整份替换。名单里必须重新包含「绑定 ∩ 启用」。只传「这个人看得见的全部技能」时，没绑给 `session-chat` 的技能会进提示词。

没有提问用户，或交集为空时，使用 `SkillFilter.none()`。

可见性只决定清单里出不出现。中间件先按过滤器裁目录，再渲染清单；`load_skill_through_path` 只查这个目录。业务代码不另写一套加载拦截，也不在发消息前把技能全文拼进用户消息。

`ChatSessions.systemPrompt` 里现有的「按 meeting-booking 技能执行」保留。它是领域提示，不代替清单。

## 3. 表

新脚本放在 `db/mysql/V11__skill_governance.sql`。和 V6 一样，由部署手工执行；应用启动不负责建技能表。启动器 `SkillGovernanceSchemaRunner` 只查 `information_schema`：缺业务列或缺少 `ai_agent_skill` 时补上，并且可以重复执行。启动器不修改已有 `source`，也不执行 `DROP INDEX`。

框架列保持 V6 的名字和类型。JDBC 方言按这些名字校验，不能改成 `gmt_create`。

### 3.1 `agentscope_skills` 补列

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `char(1) NOT NULL DEFAULT '0'` | `0` 启用，`1` 停用。与通讯录状态一致 |
| `visible_scope` | `char(1) NOT NULL DEFAULT '0'` | `0` 公开，`1` 本部门，`2` 仅本人。本仓库没有现成的资源可见范围表，取值按这个三档 |
| `owner_dept_id` | `bigint unsigned NULL` | `app_directory_department.source_id`。只在 `visible_scope='1'` 时参与比较 |
| `create_by` | `bigint unsigned NULL` | `app_user.id`。新行必填。只在 `visible_scope='2'` 时参与比较 |

存量行靠默认值成为「启用且公开」。`create_by` 允许为空，只为了接纳升级前的行；新写入的行必须有创建人。

`skill_content` 保存框架要读的完整 SKILL.md，包含 frontmatter。`source` 的值保持 `jdbc_agentscope_skills`。

物理删除。仓库按 `name` 直查，逻辑删除行仍会被加载。

### 3.2 参考资料

继续用 `agentscope_skill_resources`。主键是 `(id, resource_path)`，`id` 是技能主键，删除技能时现有外键级联删除资料。

路径是技能内相对路径：不以 `/` 开头，不含 `..`，不能是 `SKILL.md`。保存技能时先删该技能的旧资料行，再插入新的一组。

### 3.3 `ai_agent_skill`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `bigint unsigned` 自增 | 主键 |
| `agent_id` | `varchar(128) NOT NULL` | 与 `agent_session.agent_id` 一致。当前只有 `session-chat` |
| `skill_id` | `bigint NOT NULL` | `agentscope_skills.id` |
| `created_by` | `bigint unsigned NOT NULL` | 保存这次绑定的 `app_user.id` |
| `gmt_create` | `datetime(3) NOT NULL` | 插入时由现有 `MybatisPlusMetaObjectHandler` 填充 |
| `gmt_modified` | `datetime(3) NOT NULL` | 同上。覆盖写是先删后插，两列都在插入时填写 |

唯一约束 `uk_ai_agent_skill(agent_id, skill_id)`。另建 `idx_ai_agent_skill_skill(skill_id)`，删除技能前用它查绑定。不建外键，关联在服务层检查。这张表不做软删。

迁移脚本在补列之后，把当时已经存在的技能各插一行 `agent_id='session-chat'`。`created_by` 取库里任一 `is_admin=1` 的用户；没有管理员时取最小的未删除 `app_user.id`；用户表也是空的则跳过绑定，由上线后的管理员补。这样升级前已经在聊天里生效的技能，上线后仍然出现在默认公开名单里。此后新增的技能不自动绑定。

本阶段不建智能体表。运行时只有 `session-chat` 这一个智能体。

## 4. 写入

管理接口前缀 `/api/admin`。鉴权与 `GET /api/admin/directory` 相同：`CurrentUser.require()` 之后要求 `app_user.is_admin=1`，否则 403。控制器返回 `Mono`，JDBC 放在 `boundedElastic`。

错误沿用 `SkillException`：参数不合法 400，未登录 401，无权限 403，不存在 404，技能名冲突或技能仍被绑定 409。

### 4.1 保存技能

创建和修改提交：技能名、用途说明、正文、可见范围、状态、参考资料。

服务层把正文拼成 SKILL.md 后写入 `skill_content`：

```text
---
name: "meeting-booking"
description: "预订会议室并安排会议"
---

正文
```

用途说明里的引号和反斜杠按 YAML 双引号标量转义，换行改成空格。详情回显时剥掉 frontmatter，只把正文还给表单。表单再次保存时按同一规则重拼，不把剥下来的 frontmatter 原样存回。

写入约束：

- `name` 创建后不可改。格式沿用 `SkillDocuments.NAME`：`^[a-z0-9][a-z0-9-]{0,63}$`。
- 可见范围缺省为 `0`。选 `1` 又没填部门时，用当前操作人的通讯录部门；操作人没有部门则 400。
- 状态缺省为 `0`。
- `source` 插入时写 `jdbc_agentscope_skills`，之后不改。`create_by` 取当前 `app_user.id`。
- 参考资料整组覆盖。路径不合法时整次保存失败，不留半截资料。
- 修改走更新语句，更新 `description`、`skill_content`、`status`、`visible_scope`、`owner_dept_id`。不更新 `name`、`source`、`created_at`。`updated_at` 仍由列上的 `ON UPDATE CURRENT_TIMESTAMP` 维护。

`POST /api/skills/*` 与 `DELETE /api/skills/{name}` 改为 410，响应说明写入口已收到管理端。`GET /api/skills` 同样改为 410：它会把未绑定和仅本人的技能列给任意登录用户。导入改到管理接口，复用 `SkillDocuments` 解析 markdown 或 zip，然后按本节插入一行。调用方不能在导入请求里指定 `source`。

### 4.2 启停和删除

启停只改 `status`。停用后绑定行留着，当轮过滤器会丢掉。

删除：

- 存在 `ai_agent_skill` 时返回 409，先解绑。
- 无绑定时删除技能行。资料由外键级联删除。

### 4.3 保存绑定

`PUT /api/admin/agents/session-chat/skills` 覆盖写：先删 `agent_id='session-chat'` 的旧行，再插入去重后的技能 id。请求体是技能名列表。

新绑定只接受同时满足这两条的技能：`status='0'`，并且按第 5 节对当前操作人可见。已经绑上、后来停用或变得对其他人不可见的行可以留着。空列表表示解除全部绑定。

## 5. 谁在当轮看得到技能

计算在 `ChatSessions.context()` 里完成，此时已经有 `activeUserId()`。部门从通讯录表查。不在响应式线程上读登录上下文。

`activeUserId()` 不是正整数，对应用户不存在，或 `app_user.is_deleted<>0` 时，名单为空。

部门：用 `app_user.mantianxing_user_id` 找到 `app_directory_user`。用户行不存在、已删除或 `status<>'0'` 时，视为没有部门。部门行不存在、已删除或 `status<>'0'` 时，同样视为没有部门。

| `visible_scope` | 谁可见 |
| --- | --- |
| `0`，或该列为空 | 认得出的登录用户 |
| `1` | 上面算出的部门 id 等于 `owner_dept_id` |
| `2` | `create_by` 等于该 `app_user.id` |

本部门按部门 id 相等判断。不沿 `parent_id` 把下级算进来。管理端列表仍返回全部技能，方便运营。

当轮名单是下面三件事的交集，当前智能体固定为 `session-chat`：

1. `ai_agent_skill` 有这一行。
2. 技能 `status='0'`。
3. 上表对这个用户成立。

```java
List<String> names = visibility.namesFor("session-chat", activeUserId());
SkillFilter turn = names.isEmpty()
        ? SkillFilter.none()
        : SkillFilter.only(names.toArray(String[]::new));
RuntimeContext.builder()
        .userId(activeUserId())
        .sessionId(id(session))
        .put(SkillFilter.class, turn)
        .build();
```

过滤器里只放技能名。`meeting-booking_jdbc_agentscope_skills` 匹配不到。模型在清单里看到的 skill-id 仍是这个复合值，加载工具用它在当轮目录里查找。

## 6. 接口

### 6.1 技能

`GET /api/admin/skills?q=&state=&page=1&size=20`

`state`：`all`、`enabled`、`disabled`。缺省 `all`。`q` 匹配 `name` 和 `description`。`page` 从 1 开始，`size` 最大 100。

```json
{
  "items": [
    {
      "name": "meeting-booking",
      "description": "预订会议室并安排会议",
      "status": "0",
      "visibleScope": "0",
      "ownerDeptId": null,
      "ownerDeptName": null,
      "resourceCount": 1,
      "bound": true,
      "updatedAt": "2026-11-23T11:00:00"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 20
}
```

`bound` 表示是否绑在 `session-chat` 上。

`GET /api/admin/skills/{name}`

返回基本信息、纯正文、`files[]`（`path`、`content`）、`bound`。

`POST /api/admin/skills`

```json
{
  "name": "meeting-booking",
  "description": "预订会议室并安排会议",
  "content": "正文",
  "visibleScope": "0",
  "ownerDeptId": null,
  "status": "0",
  "files": [{ "path": "references/faq.md", "content": "..." }]
}
```

`name` 重复返回 409。

`PUT /api/admin/skills/{name}` 保存除 `name` 以外的上述字段。路径中的名字与库中的名字不一致时 404。

`POST /api/admin/skills/{name}/status`

```json
{ "enabled": false }
```

`DELETE /api/admin/skills/{name}` 被绑定时 409。

`POST /api/admin/skills/import` 接受 markdown 或 zip，复用 `SkillDocuments`。结果是一行技能，可见范围和状态用默认值。请求可以用查询参数覆盖 `visibleScope`、`ownerDeptId`、`status`，规则与创建相同。

`GET /api/admin/skills/options` 返回当前操作人可见且 `status='0'` 的技能名和用途说明，给绑定页使用。这个路径注册在 `/{name}` 之前。

### 6.2 绑定

`GET /api/admin/agents/session-chat`

```json
{
  "agentId": "session-chat",
  "title": "海卓大脑办公助手",
  "skills": [
    {
      "name": "meeting-booking",
      "description": "预订会议室并安排会议",
      "status": "0",
      "visibleScope": "0"
    }
  ]
}
```

`PUT /api/admin/agents/session-chat/skills`

```json
{ "names": ["meeting-booking"] }
```

其他 `agentId` 返回 404。

## 7. 运行时

```java
new JdbcAgentSkillRepository(dataSource, dialect, false)
```

方言保持 `SkillJdbc` 的现状：`enableBaseTables(false)`、`enableSkillTables(true)`、`autoCreateTable(false)`、表名 `agentscope_skills` 与 `agentscope_skill_resources`。

装配：

```java
agentBuilder
    .skillRepository(skillRepository)
    .disableDefaultWorkspaceSkills()
    .skillFilter(SkillFilter.none());
```

`disableDefaultWorkspaceSkills()` 已经存在。工作区若还有 `skills/` 目录，同名文件仍可能盖住库里的技能，部署时不要在该目录放技能包。不开启模型侧的技能管理工具。

当轮名单为空时，中间件安装空目录，提示词里没有技能清单。名单非空时，框架注册 `load_skill_through_path`，模型按清单里的 skill-id 读取 SKILL.md，再按正文里的相对路径读取参考资料。

`startStored()` 只用用户 id 去列已落盘会话，不调用模型，那里的上下文不放技能过滤器。

## 8. 页面

`CapabilityManagement` 的 Skills 页去掉 `sessionStorage` 键 `haizhuo-admin-mock-v2` 上的技能读写，改走第 6.1 节。

- 列表展示名称、用途说明、启用或停用、可见范围、是否已绑定、更新时间。
- 表单提交技能名、用途说明、正文、可见范围、状态、参考资料。技能名在创建后不可编辑。
- 可见范围为「本部门」时，部门从 `GET /api/admin/directory` 选择，提交 `ownerDeptId`。
- 原文预览展示拼好的 SKILL.md；编辑区展示纯正文。
- Agent 页只保留一条「海卓大脑办公助手」，标识显示 `session-chat`。勾选来自 `GET /api/admin/skills/options`，保存走第 6.2 节。

MCP 页、草稿发布、版本号、近 24 小时调用量、变更记录继续用页面里的演示数据。`UserPermissions` 的部门授权和个人排除也继续用演示数据。技能是否对某人可见，由技能行上的 `visible_scope` 决定。

页脚里 Skills 相关的「保存在本浏览器会话」在接口接上后删除。

## 9. 包与类

沿用 `com.beyondzy.demo` 的分层，功能名用 `skill`。实体写法与现有 `SkillBean` 一致：MyBatis-Plus 注解，手写访问器。框架表的时间列继续映射 `created_at` / `updated_at`，不走 `gmtCreate` 填充。

| 落点 | 类 |
| --- | --- |
| `domain.skill` | 扩展现有 `SkillBean`、`SkillResourceBean`。新增 `AgentSkillBean` |
| `enums.skill` | `SkillVisibleScope`：`PUBLIC("0")`、`DEPARTMENT("1")`、`OWNER("2")` |
| `mapper.skill` | 扩展现有 `SkillMapper`、`SkillResourceMapper`。新增 `AgentSkillMapper` |
| `service.skill` | 新增 `SkillAdminService`、`SkillVisibilityService`。现有 `SkillCatalogService` 的写方法改为抛 410 |
| `service.skill.impl` | 对应实现。绑定行不手写 `gmtCreate` |
| `web.skill` | 新增 `SkillAdminController`、`AgentSkillAdminController`。请求和响应放 `req` / `res` |
| `config.skill` | 新增 `SkillGovernanceSchemaRunner`。`ChatConfiguration` 里的仓库改为只读 |
| `exception.skill` / `handler.skill` | 沿用 `SkillException` 和 `SkillExceptionHandler` |

`SkillVisibilityService.namesFor(String agentId, String appUserId)` 是聊天唯一要调用的方法。`ChatSessions.context` 注入该服务。

## 10. 执行顺序

每步做完应能单独验证，再进入下一步。

1. **表和只读仓库**  
   写 `V11__skill_governance.sql` 和 `SkillGovernanceSchemaRunner`。仓库改为只读，并把当时已有的技能绑到 `session-chat`。  
   验收：空库在执行 V6 和 V11 后能启动；已有 V6 的库启动两次，第二次不失败。`getSkill(name)` 读回正文和参考资料，`source` 仍是 `jdbc_agentscope_skills`。`isWriteable()` 为 false，调用 `save` 后行数不变。

2. **技能管理接口**  
   增删改、资料整组替换、导入、启停、有绑定时拒绝删除。停用 ` /api/skills` 的读写。  
   验收：保存后 `getSkill(name)` 读到带 frontmatter 的 SKILL.md；详情接口返回纯正文。非法路径整次失败。重复 `name` 返回 409。技能名在 `PUT` 中保持不变。

3. **绑定**  
   覆盖写 `session-chat`。  
   验收：重复保存同一组名字只有一组行。空列表删光绑定。新绑定拒绝停用技能，也拒绝当前操作人不可见的技能。已绑定技能再停用时，行还在，删除技能返回 409。

4. **当轮过滤器**  
   装配 `SkillFilter.none()`，`context()` 放入 `namesFor("session-chat", userId)`。  
   验收：同一智能体、两个用户。公开技能两边都有；本部门技能只有部门 id 相同的人有；仅本人技能只有 `create_by` 有；未绑定的公开技能没有；用户 id 无法解析时名单为空。用名单外的 skill-id 调用 `load_skill_through_path` 得到找不到。用户消息里没有技能全文。

5. **页面**  
   按第 8 节接上接口，并用浏览器走通列表、保存、启停、可见范围、绑定。  
   验收：刷新后技能和绑定仍在。Skills 页不再读写 `haizhuo-admin-mock-v2`。

测试放在 `ag-ui` 现有的 Spring 测试风格里：正文投影、绑定冲突、两个部门用户、过滤器四条边界。不连真实模型。

## 11. 不做

- 不改 AgentScope 的列名、`SkillFilter.only` 的替换语义和加载工具名。
- 不给框架表加 `namespace`，也不把 `source` 改成别的常量。
- 不把技能正文再复制到另一张业务表。
- 不做草稿、发布、版本固定、调用统计、变更流水和 MCP 工具勾选。
- 不做部门授权表和个人排除表。本部门不沿父部门向下展开。
- 不把 `session-chat` 改成 `office-assistant`，也不在本阶段做多个可运行智能体。
- 不在无人值守、认不出用户的调用里退回全表技能。
