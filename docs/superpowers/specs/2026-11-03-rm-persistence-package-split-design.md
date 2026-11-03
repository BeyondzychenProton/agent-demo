# Design: rm_* 全量迁入 domain/service/mapper 并按功能分目录

日期：2026-11-03  
范围：`ag-ui` 模块持久化层重组

## Goal

1. 将 `ReadModelStore` 中全部 `rm_*` JDBC 迁入 MyBatis Mapper + Persistence Service，去掉 JDBC 双路径。
2. 将现有 `domain` / `mapper` / `service` 按功能拆为四个子包：`user` / `session` / `display` / `readmodel`。
3. 全部应用服务统一为 interface + impl，并补齐中文职责注释。

## Decisions (locked)

| 决策点 | 选择 |
|--------|------|
| rm_* 迁出深度 | 全部迁出（读、写、分页、变更、内容、清理） |
| 功能分包 | 四包：`user` / `session`（含 agent_session + session_object）/ `display` / `readmodel` |
| Service 风格 | 全部 interface + impl |
| Mapper 粒度 | 方案 A：每功能族一个 Mapper（`ReadModelMapper` 收齐全部 `rm_*` SQL） |
| 业务编排包 | `session/`、`readmodel/` 等编排类不搬包，只改 import |

## Package layout

```
com.beyondzy.demo
├── domain/
│   ├── user/          UserBean, BaseAuditBean
│   ├── session/       AgentSessionBean, SessionObjectBean
│   ├── display/       DisplayMessageBean, DisplayTurnBean, DisplayViewBean
│   └── readmodel/     ReadModelHeadBean + 新增读模型行 Bean
├── mapper/
│   ├── user/          UserMapper
│   ├── session/       AgentSessionMapper, SessionObjectMapper
│   ├── display/       DisplayMapper
│   └── readmodel/     ReadModelMapper
└── service/
    ├── user/          UserService + impl/UserServiceImpl
    ├── session/       AgentSessionService, SessionObjectService + impl/*
    ├── display/       DisplayIndexService + impl/DisplayIndexServiceImpl
    └── readmodel/     ReadModelPersistenceService + impl/ReadModelPersistenceServiceImpl
```

Spring：`@MapperScan("com.beyondzy.demo.mapper")` 保持，依赖其子包扫描；若实测扫不到再改为显式四包。  
注入类型：调用方依赖接口，不再依赖具体 `@Service` 实现类。

## readmodel responsibilities

### Domain beans

- 已有：`ReadModelHeadBean`（`rm_head`）
- 新增（按查询结果需要；标量够用则不加 Bean）：
  - `ReadModelItemPayloadBean`：当前条目 payload
  - `ReadModelChangeRowBean`：`revision + payload`
  - `ReadModelContentBlockBean`：`body + encoding`
  - `ReadModelStaleItemBean`：`thread + id + revision`
- `rm_raw` / `rm_pending` / `rm_block` 写路径用参数直传，不强制建 Bean

### ReadModelMapper

集中全部 `rm_*` SQL：

- 读：head / raw / current order / max order / pending payloads / pending count / current item payload / page（两条）/ changes / content blocks / stale items
- 写：upsert raw / close item version / insert item / insert change / delete+insert pending / upsert head / insert ignore block / insert content
- 删：按 thread 清 content/change/pending/item/raw/head；cleanup 相关 delete change/item/content/block

方法注释标明操作的表与语义。

### ReadModelPersistenceService（interface + impl）

- `@Transactional`：`invalidate`、`commit`、`cleanup`
- 只读方法：`readOnly = true`
- `commit`：接收 Store 算好的写指令批次（如 `CommitBatch`：raw、item 关闭/插入、change、pending、block、content、head），按现有顺序执行，同一事务提交
- `page` / `changes` / `content`：只做 SQL 与行映射；字节预算、游标合法性、`Gone` 仍在 Store

### ReadModelStore（编排保留）

保留：

- 预览截断、分块哈希、指纹比对、HITL pending 判定
- 游标编解码、分页字节预算、事件续读合法性

移除：

- `DataSource` / `Connection` 与全部 JDBC 双路径（`persistence == null` 回退）
- 构造仅注入 `ReadModelPersistenceService`

## display / session / user migration

- 只改包路径与 import，行为不变
- `DisplayIndexService` 从具体类拆为接口 + `DisplayIndexServiceImpl`；`Snapshot` / `Message` / `Turn` record 留在接口上
- 调用方更新 import：`MySqlSessionLogStore`、`ThreadCatalog`、`DisplayViewStore`、`ChatSessions`、`ChatConfiguration`、`ProjectionService`、`ReadModelStore`

## Comment conventions

- 类：一句话职责 + 对应表族
- 接口方法：业务意图，不写 SQL
- Mapper 方法：操作的表与语义（读水位 / 关闭旧版本 / 幂等 upsert 等）
- 复杂事务（`commit`、`cleanup`、`saveSnapshot`）：说明同事务、顺序、为何一起提交
- 不为 getter/setter 写空话注释

## Out of scope

- 不改表结构 / Flyway 脚本
- 不搬 `session/`、`readmodel/` 业务编排包位置
- 不改对外 HTTP API 与投影语义
- 不做无关重构（如重命名对外 DTO）

## Success criteria

1. `ReadModelStore` 中无 JDBC / `Connection` / `PreparedStatement`
2. 全部 `rm_*` SQL 仅出现在 `mapper.readmodel.ReadModelMapper`
3. `domain` / `mapper` / `service` 仅按四功能子包组织，旧扁平包路径不再保留类
4. Display / ReadModel 均为 interface + impl；注入点使用接口
5. 相关类与关键方法具备符合约定的中文注释
6. 现有测试可编译运行，投影读写行为与迁前一致
