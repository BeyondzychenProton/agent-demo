# Implementation Plan: rm_* 全量迁入 + 四功能分包

> **For agentic workers:** REQUIRED SUB-SKILL: Execute implementation plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Complete them in order; do not skip verification.

**Goal:** 将全部 `rm_*` JDBC 迁入 `domain/mapper/service.readmodel`，并把现有持久化层按 `user` / `session` / `display` / `readmodel` 四包重组；Service 统一为 interface + impl，补齐中文注释。

**Architecture:** 编排类（`ReadModelStore`、`DisplayViewStore`、`MySqlSessionLogStore` 等）留在原包；SQL 与事务进 Mapper / PersistenceService。`ReadModelStore` 只做截断、指纹、游标、HITL 判定，通过 `CommitBatch` 把写指令交给 PersistenceService。

**Tech Stack:** Java 21、Spring Boot、MyBatis（注解 Mapper）、现有 JUnit 测试。

**Spec:** `docs/superpowers/specs/2026-11-03-rm-persistence-package-split-design.md`

---

## Task 1: 分包搬迁 user / session（行为不变）

**Files:**
- Move: `domain/UserBean.java`, `domain/BaseAuditBean.java` → `domain/user/`
- Move: `domain/AgentSessionBean.java`, `domain/SessionObjectBean.java` → `domain/session/`
- Move: `mapper/UserMapper.java` → `mapper/user/`
- Move: `mapper/AgentSessionMapper.java`, `mapper/SessionObjectMapper.java` → `mapper/session/`
- Move: `service/UserService.java` → `service/user/`
- Move: `service/impl/UserServiceImpl.java` → `service/user/impl/`
- Move: `service/AgentSessionService.java`, `service/SessionObjectService.java` → `service/session/`
- Move: `service/impl/AgentSessionServiceImpl.java`, `service/impl/SessionObjectServiceImpl.java` → `service/session/impl/`
- Update imports: `MySqlSessionLogStore.java`；任何引用旧包的文件

- [ ] **Step 1:** 创建目标目录，移动文件并改 `package` 声明与内部 import
- [ ] **Step 2:** 更新调用方 import 到新包
- [ ] **Step 3:** 确认类注释仍符合约定（职责 + 表族）；缺则补
- [ ] **Step 4:** 编译检查

```powershell
cd ag-ui; mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5:** Commit

```powershell
git add -A ag-ui/src/main/java/com/beyondzy/demo/domain ag-ui/src/main/java/com/beyondzy/demo/mapper ag-ui/src/main/java/com/beyondzy/demo/service ag-ui/src/main/java/com/beyondzy/demo/session/MySqlSessionLogStore.java
git commit -m "refactor: split user/session persistence into feature packages"
```

---

## Task 2: 分包搬迁 display + 拆 interface/impl

**Files:**
- Move: `domain/Display*.java` → `domain/display/`
- Move: `mapper/DisplayMapper.java` → `mapper/display/`
- Create: `service/display/DisplayIndexService.java`（接口，含 `Snapshot`/`Message`/`Turn` record）
- Create: `service/display/impl/DisplayIndexServiceImpl.java`（现有实现迁入，加 `@Service`）
- Delete: 旧 `service/DisplayIndexService.java`
- Update imports: `ThreadCatalog`, `DisplayViewStore`, `ChatSessions`, `ChatConfiguration`

- [ ] **Step 1:** 搬 Bean / Mapper 到 `display` 子包
- [ ] **Step 2:** 将现有 `DisplayIndexService` 拆为接口 + Impl；方法签名与 record 不变；Impl 保留 `@Transactional` 与现有逻辑
- [ ] **Step 3:** 接口与 Impl、Mapper 补中文注释（含 `saveSnapshot` 同事务说明）
- [ ] **Step 4:** 更新所有注入/import 使用 `com.beyondzy.demo.service.display.DisplayIndexService`
- [ ] **Step 5:** 编译

```powershell
cd ag-ui; mvn -q -DskipTests compile
```

- [ ] **Step 6:** Commit

```powershell
git commit -m "refactor: move display persistence to feature package with interface+impl"
```

---

## Task 3: 建立 readmodel domain Bean + 扩展 Mapper 写/读 SQL

**Files:**
- Move: `domain/ReadModelHeadBean.java` → `domain/readmodel/`
- Create: `domain/readmodel/ReadModelItemPayloadBean.java`（`payload`）
- Create: `domain/readmodel/ReadModelChangeRowBean.java`（`revision`, `payload`）
- Create: `domain/readmodel/ReadModelContentBlockBean.java`（`body`, `encoding`）
- Create: `domain/readmodel/ReadModelStaleItemBean.java`（`thread`, `id`, `revision`）
- Move+expand: `mapper/ReadModelMapper.java` → `mapper/readmodel/ReadModelMapper.java`

Mapper 必须覆盖当前 `ReadModelStore` 全部 SQL，至少包括：

| 方法 | SQL 语义 |
|------|----------|
| 已有 find/delete | 保持 |
| `findCurrentPayload` | `SELECT payload FROM rm_item ... until_revision IS NULL` |
| `findPageCurrent` / `findPageAtRevision` | 两条 page SQL |
| `findMinChangeRevision` | `SELECT MIN(revision) FROM rm_change` |
| `findChangesAfter` | change JOIN item |
| `findContentBlocks` | content JOIN block |
| `findStaleItems` | cleanup 用 SELECT |
| `upsertRaw` / `closeItemVersion` / `insertItem` / `insertChange` | commit 写路径 |
| `deletePending` / `insertPending` | pending 维护 |
| `upsertHead` | head upsert |
| `insertBlockIgnore` / `insertContent` | 内容分块 |
| `deleteChangesBefore` / `deleteItemVersion` / `deleteOrphanContent` / `deleteOrphanBlocks` | cleanup |

- [ ] **Step 1:** 建 Bean（getter/setter；类注释说明对应查询用途）
- [ ] **Step 2:** 扩展 Mapper，每个方法写表语义注释；SQL 从 `ReadModelStore` 原样迁出，不改语义
- [ ] **Step 3:** 编译（此时 Store 仍可用旧 PersistenceService，暂可并存旧包路径到 Task 4）

```powershell
cd ag-ui; mvn -q -DskipTests compile
```

- [ ] **Step 4:** Commit

```powershell
git commit -m "feat: expand readmodel mapper and domain beans for full rm_* SQL"
```

---

## Task 4: ReadModelPersistenceService interface + impl（含 CommitBatch）

**Files:**
- Create: `service/readmodel/ReadModelPersistenceService.java`（接口）
- Create: `service/readmodel/CommitBatch.java`（写指令 record；可与接口同包）
- Create: `service/readmodel/impl/ReadModelPersistenceServiceImpl.java`
- Delete: 旧 `service/ReadModelPersistenceService.java`

**接口方法（与现有行为对齐）：**
- `state` / `raw` / `order` / `maxOrder` / `pending` / `pendingCount` / `invalidate`
- `findCurrentPayload(thread, id)` → String
- `commit(CommitBatch batch)` `@Transactional`
- `findPagePayloads(...)` / `findMinChangeRevision` / `findChangesAfter` / `findContentBlocks`
- `cleanup(long changeTtlCutoff, long snapshotTtlCutoff)` `@Transactional`

**CommitBatch 建议字段（按 commit 循环顺序）：**
- `List<RawWrite> raws`
- `List<ItemWrite> items`（每项含：id、revision、orderKey、payload、pendingOrderKeyOrNull、List\<BlockWrite\> blocks）
- `HeadWrite head`（thread、generation、revision、execSeq、inboxSeq、updatedAt、leaseTag、checkpoint、control）

Impl 中 `commit`：按 raws → 各 item（close → insert item → change → delete pending → optional insert pending → blocks/content）→ head 顺序调用 Mapper。

- [ ] **Step 1:** 定义接口与 `CommitBatch` record，写清中文注释
- [ ] **Step 2:** 实现 Impl，迁移现有读/invalidate 逻辑，新增 commit/cleanup/page 相关委托
- [ ] **Step 3:** 暂时让旧 import 能编译：若调用方仍指向旧类，本步同步改 import（见 Task 5 可合并）
- [ ] **Step 4:** 编译

```powershell
cd ag-ui; mvn -q -DskipTests compile
```

- [ ] **Step 5:** Commit

```powershell
git commit -m "feat: add ReadModelPersistenceService interface with transactional commit batch"
```

---

## Task 5: 重写 ReadModelStore — 去掉 JDBC

**Files:**
- Modify: `readmodel/ReadModelStore.java`
- Update: `ProjectionService`, `ChatSessions`, `ChatConfiguration` import/构造

**改造要点：**
1. 删除 `DataSource`/`Connection` 字段与所有 `prepareStatement` 路径
2. 构造只接收 `ReadModelPersistenceService`（可保留废弃的 `Path`/`DataSource` 构造抛 `UnsupportedOperationException`，或直接删除并修测试）
3. `state`/`raw`/`order`/`maxOrder`/`pending`/`pendingCount`/`invalidate` 直接调 persistence
4. `commit`：本地跑 preview/fingerprint/HITL 逻辑，组装 `CommitBatch`，再 `persistence.commit(batch)`；`preview(..., write=true)` 时把 block 写入指令放进 batch，不再直接写库
5. `page`/`changes`/`content`/`cleanup`：业务校验留 Store，SQL 走 persistence
6. 确认文件中无 `java.sql` import

- [ ] **Step 1:** 重写 Store；`preview` 改为返回 ViewItem + 待写 blocks（或在 write 模式下收集到外部 list）
- [ ] **Step 2:** 更新 `ProjectionService` / `ChatConfiguration` / `ChatSessions` 构造与 import
- [ ] **Step 3:** 检查测试中手动 `new ReadModelStore(dataSource)` 的地方，改为注入 persistence 或测双路径删除后的新构造

```powershell
cd ag-ui; rg "new ReadModelStore|javax\.sql|java\.sql" src
```

- [ ] **Step 4:** 编译 + 相关测试

```powershell
cd ag-ui; mvn -q -Dtest=ProjectionServiceTest,CommandProjectionTest,ReadModelTestServer test
```

若个别测试类名不同，改为：

```powershell
cd ag-ui; mvn -q -Dtest=*readmodel* test
```

- [ ] **Step 5:** Commit

```powershell
git commit -m "refactor: remove JDBC from ReadModelStore; persist via MyBatis service"
```

---

## Task 6: 收尾 — MapperScan、注释、扁平包清空、全量验证

**Files:**
- Possibly modify: `ChatApplication.java`（若子包扫描失败，改为显式四包）
- Verify: `domain/`、`mapper/`、`service/` 根下无残留 `.java`（除子包外）
- Verify: `service/impl/` 空目录已删除

- [ ] **Step 1:** 列出扁平包残留并删除空目录

```powershell
Get-ChildItem -Recurse ag-ui\src\main\java\com\beyondzy\demo\domain,ag-ui\src\main\java\com\beyondzy\demo\mapper,ag-ui\src\main\java\com\beyondzy\demo\service -File
```

Expected: 仅子包内有文件

- [ ] **Step 2:** 抽查注释是否符合约定（类 / 接口方法 / Mapper / 事务方法）
- [ ] **Step 3:** 全量编译与测试

```powershell
cd ag-ui; mvn -q test
```

- [ ] **Step 4:** 成功标准自检
  - [ ] `ReadModelStore` 无 JDBC
  - [ ] `rm_*` SQL 仅在 `mapper/readmodel/ReadModelMapper`
  - [ ] 四功能子包完整；旧扁平路径无类
  - [ ] Display / ReadModel 均为 interface + impl
  - [ ] 测试通过

- [ ] **Step 5:** Commit

```powershell
git commit -m "chore: finish persistence package split and verify mapper scan"
```

---

## Risk notes

- `commit` 内指纹与分块逻辑必须留在 Store；若误放进 Service，易产生循环依赖或泄漏 `ViewItem` 编解码。
- MyBatis `@MapperScan` 默认扫子包；若启动报 mapper 缺失，在 `ChatApplication` 改为四个 basePackages。
- 测试若使用真实 MySQL，需保证 V4 表已存在；本计划不改 schema。
