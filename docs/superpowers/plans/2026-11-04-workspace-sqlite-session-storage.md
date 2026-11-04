# Workspace SQLite Session Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all MySQL/MyBatis persistence and restore Path-based workspace SQLite for SessionLog, thread catalog, display views, and read models.

**Architecture:** `WorkspaceSessionLogStore` owns authoritative SessionLog under `.agentscope-runtime/`. Application indexes use JDBC SQLite files `thread-catalog.sqlite3` and `read-model.sqlite3` in `chat.workspace`. Spring assembly returns to `new ChatSessions(workspace, model)` with fixed `userId=local-user`.

**Tech Stack:** Java 21, Spring Boot 4 WebFlux, AgentScope harness `2.1.0-BETA1` (`WorkspaceSessionLogStore` / `LocalFilesystem`), `org.xerial:sqlite-jdbc`, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-04-workspace-sqlite-session-storage-design.md`

## Global Constraints

- No MySQL, MyBatis-Plus, `spring.datasource.*`, or `db/mysql` migrations remain after the final task
- Session identity `userId` is the string `local-user` (not a numeric `app_user.id`)
- SQLite dialect only: `ON CONFLICT … DO UPDATE` / `INSERT OR IGNORE`; never MySQL `ON DUPLICATE KEY` / `INSERT IGNORE` / `VALUES(col)`
- Do not migrate existing MySQL data; empty workspace creates tables on open
- HTTP/AG-UI contracts unchanged
- Working directory for Maven: repo root; module path `ag-ui` (`mvn -f ag-ui/pom.xml …` or `cd ag-ui && mvn …`)

## Review Focus

- Process crash mid-`commit` on `read-model.sqlite3` must not leave a half-applied revision visible after reopen (transaction rollback)
- `DisplayViewStore.save` with an older `seq` after a newer one must leave the newer payload readable
- Corrupted `display_view.payload` / unknown `format` must return miss, not throw to callers of `read`
- `ChatSessions` reopen after JVM restart must list the same session ids via `WorkspaceSessionLogStore.list`
- Removing MyBatis must not leave orphan `@Autowired` / `@MapperScan` compile failures in controllers

## File Structure

| File | Responsibility |
|------|----------------|
| `ag-ui/src/main/java/.../session/ThreadCatalog.java` | Path → `thread-catalog.sqlite3`; catalog + display snapshot tables |
| `ag-ui/src/main/java/.../session/DisplayViewStore.java` | Path → same file; `display_view` cache |
| `ag-ui/src/main/java/.../readmodel/ReadModelStore.java` | Path → `read-model.sqlite3`; `rm_*` |
| `ag-ui/src/main/java/.../readmodel/ProjectionService.java` | Path-only constructor wrapping `ReadModelStore` |
| `ag-ui/src/main/java/.../session/ChatSessions.java` | `(Path, Model)` only; wires WorkspaceSessionLogStore + Path stores |
| `ag-ui/src/main/java/.../config/ChatConfiguration.java` | Build `ChatSessions(workspace, model)` |
| `ag-ui/pom.xml` / `application.properties` / `.env.example` | Drop MySQL stack; add sqlite-jdbc |
| Delete | `MySqlSessionLogStore`, `domain/**`, `mapper/**`, `service/**`, `AuditMetaObjectHandler`, `db/mysql/**` |
| Restore tests from `main` | `ThreadCatalogTest`, `DisplayViewStoreTest`, `ReadModelStoreTest`, `HitlIntegrationTest` (remove `@Disabled`) |

---

### Task 1: ThreadCatalog Path + SQLite

**Files:**
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/session/ThreadCatalog.java`
- Create: `ag-ui/src/test/java/com/beyondzy/demo/session/ThreadCatalogTest.java` (copy from `main`, remove `@Disabled`)

**Interfaces:**
- Consumes: none
- Produces: `ThreadCatalog(Path workspace)`; keeps existing public/package API (`get`, `sync`, `submitted`, `retain`, `page`, `display`, `previousDisplay`, `saveDisplay`, records `Summary`/`Page`/`DisplaySnapshot`/…)

- [ ] **Step 1: Restore failing test from main**

Copy `main:ag-ui/src/test/java/com/beyondzy/demo/session/ThreadCatalogTest.java`, delete the `@Disabled` annotation. Keep all four tests and assertions as on `main`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f ag-ui/pom.xml -Dtest=ThreadCatalogTest test`

Expected: FAIL — `UnsupportedOperationException` or missing Path constructor behavior / missing sqlite driver.

- [ ] **Step 3: Implement Path SQLite `ThreadCatalog`**

Replace MyBatis/`DataSource` fields with a single `Path`/`Connection` (or open-per-call) to `workspace.resolve("thread-catalog.sqlite3")`.

On open, `CREATE TABLE IF NOT EXISTS` for:

- `thread_catalog(id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, seq INTEGER NOT NULL, named INTEGER NOT NULL)`
- `display_head(thread_id TEXT PRIMARY KEY, seq INTEGER NOT NULL)`
- `display_message(thread_id TEXT NOT NULL, ordinal INTEGER NOT NULL, message_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, PRIMARY KEY(thread_id, ordinal))`
- `display_turn(thread_id TEXT NOT NULL, ordinal INTEGER NOT NULL, run_id TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(thread_id, ordinal))`

Implement catalog CRUD with plain JDBC. Upsert head with `ON CONFLICT(thread_id) DO UPDATE SET seq=excluded.seq`. Remove all `ThreadCatalogService` / `ThreadCatalogBean` usage from this class. Delete deprecated stub that only throws.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f ag-ui/pom.xml -Dtest=ThreadCatalogTest test`

Expected: PASS (add `org.xerial:sqlite-jdbc` to `pom.xml` in this task if Step 2 failed for missing driver; keep mybatis/mysql until Task 5 so the rest of the app still compiles).

- [ ] **Step 5: Commit**

```bash
git add ag-ui/src/main/java/com/beyondzy/demo/session/ThreadCatalog.java \
  ag-ui/src/test/java/com/beyondzy/demo/session/ThreadCatalogTest.java ag-ui/pom.xml
git commit -m "feat: restore ThreadCatalog workspace SQLite store"
```

---

### Task 2: DisplayViewStore Path + SQLite

**Files:**
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/session/DisplayViewStore.java`
- Create: `ag-ui/src/test/java/com/beyondzy/demo/session/DisplayViewStoreTest.java` (from `main`, remove `@Disabled`)

**Interfaces:**
- Consumes: same `thread-catalog.sqlite3` file convention as Task 1
- Produces: `DisplayViewStore(Path workspace)`; package methods `read(String,long)`, `save(String,long,boolean,ThreadView.SnapshotResponse)`

- [ ] **Step 1: Restore failing test from main**

Copy `DisplayViewStoreTest` from `main`, remove `@Disabled`. Assertions stay: reopen equality, old seq cannot overwrite, corrupt payload / format=999 → `read` returns null.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f ag-ui/pom.xml -Dtest=DisplayViewStoreTest test`

Expected: FAIL — Path constructor throws or MySQL SQL rejects on SQLite.

- [ ] **Step 3: Implement Path SQLite `DisplayViewStore`**

Open `workspace.resolve("thread-catalog.sqlite3")`. Ensure `display_view(thread_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, format INTEGER NOT NULL, has_interactions INTEGER NOT NULL, payload TEXT NOT NULL)`.

`save` upsert:

```sql
INSERT INTO display_view(thread_id,seq,format,has_interactions,payload) VALUES(?,?,?,?,?)
ON CONFLICT(thread_id) DO UPDATE SET
  payload=CASE WHEN excluded.seq>=display_view.seq THEN excluded.payload ELSE display_view.payload END,
  has_interactions=CASE WHEN excluded.seq>=display_view.seq THEN excluded.has_interactions ELSE display_view.has_interactions END,
  format=CASE WHEN excluded.seq>=display_view.seq THEN excluded.format ELSE display_view.format END,
  seq=MAX(display_view.seq, excluded.seq)
```

Keep validation that rejects mismatched seq / active run / persistable HITL canRespond. Decode failures → null.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f ag-ui/pom.xml -Dtest=DisplayViewStoreTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ag-ui/src/main/java/com/beyondzy/demo/session/DisplayViewStore.java \
  ag-ui/src/test/java/com/beyondzy/demo/session/DisplayViewStoreTest.java
git commit -m "feat: restore DisplayViewStore workspace SQLite store"
```

---

### Task 3: ReadModelStore + ProjectionService Path

**Files:**
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/readmodel/ReadModelStore.java`
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/readmodel/ProjectionService.java`
- Create: `ag-ui/src/test/java/com/beyondzy/demo/readmodel/ReadModelStoreTest.java` (from `main`, remove `@Disabled`)

**Interfaces:**
- Consumes: none beyond existing record types on `ReadModelStore`
- Produces: `ReadModelStore(Path workspace)` opening `workspace.resolve("read-model.sqlite3")`; `ProjectionService(..., Path workspace)` only (delete `DataSource` constructor)

- [ ] **Step 1: Restore failing test from main**

Copy `ReadModelStoreTest` from `main`, remove `@Disabled`. Keep pagination isolation, chunked long content, and reopen/cursor rejection tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f ag-ui/pom.xml -Dtest=ReadModelStoreTest test`

Expected: FAIL — Path constructor throws.

- [ ] **Step 3: Implement Path SQLite `ReadModelStore` and trim `ProjectionService`**

Replace `DataSource` constructor with Path: open JDBC `jdbc:sqlite:<read-model.sqlite3>`, create `rm_head`/`rm_raw`/`rm_item`/`rm_pending`/`rm_change`/`rm_block`/`rm_content` with SQLite types (`TEXT`/`INTEGER`), primary keys matching current MySQL V4 semantics.

Rewrite every `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` to SQLite (`ON CONFLICT DO UPDATE` / `INSERT OR IGNORE`). Keep method signatures (`state`, `invalidate`, `commit`, `page`, `changes`, `content`, …) unchanged.

`ProjectionService`: keep `ProjectionService(Function, Path)` and `ProjectionService(ChatSessions, Path)`; delete `DataSource` overload.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f ag-ui/pom.xml -Dtest=ReadModelStoreTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ag-ui/src/main/java/com/beyondzy/demo/readmodel/ReadModelStore.java \
  ag-ui/src/main/java/com/beyondzy/demo/readmodel/ProjectionService.java \
  ag-ui/src/test/java/com/beyondzy/demo/readmodel/ReadModelStoreTest.java
git commit -m "feat: restore ReadModelStore workspace SQLite store"
```

---

### Task 4: ChatSessions + WorkspaceSessionLogStore wiring

**Files:**
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/session/ChatSessions.java`
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/config/ChatConfiguration.java`
- Modify: `ag-ui/src/test/java/com/beyondzy/demo/web/agui/AguiQueuedRunTest.java` (remove `@Disabled`)
- Create: `ag-ui/src/test/java/com/beyondzy/demo/session/HitlIntegrationTest.java` (from `main`, remove `@Disabled`)

**Interfaces:**
- Consumes: Task 1–3 Path constructors; `new WorkspaceSessionLogStore(new LocalFilesystem(workspace))`
- Produces: `public ChatSessions(Path workspace, Model model)` as the only public constructor; `userId` field = `"local-user"`

- [ ] **Step 1: Enable integration tests that construct `ChatSessions(workspace, model)`**

Remove `@Disabled` from `AguiQueuedRunTest`. Restore `HitlIntegrationTest` from `main` without `@Disabled`.

- [ ] **Step 2: Run one test to verify it fails**

Run: `mvn -f ag-ui/pom.xml -Dtest=AguiQueuedRunTest test`

Expected: FAIL — constructor still requires MySQL services / throws.

- [ ] **Step 3: Rewire `ChatSessions` and `ChatConfiguration`**

In `ChatSessions(Path workspace, Model model)`:

- `store = new WorkspaceSessionLogStore(new LocalFilesystem(workspace.toAbsolutePath()))`
- `userId = "local-user"`
- build `HarnessAgent` as today with `.workspace(...).sessionLogStore(store)`
- `catalog = new ThreadCatalog(workspace)`
- `displayViews = new DisplayViewStore(workspace)`
- `projection = new ProjectionService(this::log, workspace)`
- keep existing startup `list` / `sync` / `retain` behavior

Delete constructors that take `ThreadCatalogService` / `SessionLogStore` / `DataSource`.

`ChatConfiguration.sessions(...)`: only model + `@Value("${chat.workspace}")`; `return new ChatSessions(Files.createDirectories(Path.of(workspace)), model);` — no MySQL beans.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f ag-ui/pom.xml -Dtest=AguiQueuedRunTest,HitlIntegrationTest,ProjectionServiceTest,IncrementalDisplayTest test`

Expected: PASS (skip or fix any test still compiling against deleted MySQL constructors).

- [ ] **Step 5: Commit**

```bash
git add ag-ui/src/main/java/com/beyondzy/demo/session/ChatSessions.java \
  ag-ui/src/main/java/com/beyondzy/demo/config/ChatConfiguration.java \
  ag-ui/src/test/java/com/beyondzy/demo/web/agui/AguiQueuedRunTest.java \
  ag-ui/src/test/java/com/beyondzy/demo/session/HitlIntegrationTest.java
git commit -m "feat: wire ChatSessions to WorkspaceSessionLogStore"
```

---

### Task 5: Delete MySQL stack and verify clean build

**Files:**
- Delete: `ag-ui/src/main/java/com/beyondzy/demo/session/MySqlSessionLogStore.java`
- Delete: `ag-ui/src/main/java/com/beyondzy/demo/domain/**`
- Delete: `ag-ui/src/main/java/com/beyondzy/demo/mapper/**`
- Delete: `ag-ui/src/main/java/com/beyondzy/demo/service/**`
- Delete: `ag-ui/src/main/java/com/beyondzy/demo/config/AuditMetaObjectHandler.java`
- Delete: `ag-ui/src/main/resources/db/mysql/**`
- Modify: `ag-ui/src/main/java/com/beyondzy/demo/ChatApplication.java` (remove `@MapperScan` + import)
- Modify: `ag-ui/pom.xml` (remove mybatis-plus, mysql-connector-j, spring-security-crypto; keep sqlite-jdbc)
- Modify: `ag-ui/src/main/resources/application.properties` (remove datasource, mybatis-plus, `chat.user-id`)
- Modify: `.env.example` (remove `MYSQL_*`, `CHAT_USER_ID`)

**Interfaces:**
- Consumes: Task 4 assembly with no MySQL beans
- Produces: module compiles and tests without mysql/mybatis on classpath

- [ ] **Step 1: Write a classpath assertion test (or use Maven dependency check)**

Add nothing new if avoidable: after deletions, run a full test + `mvn -f ag-ui/pom.xml dependency:tree` and assert output contains `sqlite-jdbc` and does **not** contain `mysql-connector-j` or `mybatis-plus`.

- [ ] **Step 2: Delete MySQL persistence code and clean config**

Remove listed files/dirs. Strip `@MapperScan` from `ChatApplication`. Clean properties and `.env.example` per spec. Grep the module for `mysql`, `mybatis`, `DataSource`, `JdbcTemplate`, `MapperScan` and fix any remaining references.

- [ ] **Step 3: Run full module tests**

Run: `mvn -f ag-ui/pom.xml test`

Expected: BUILD SUCCESS; no disabled tests left that cite MySQL.

- [ ] **Step 4: Dependency tree check**

Run: `mvn -f ag-ui/pom.xml dependency:tree`

Expected: `sqlite-jdbc` present; `mysql-connector-j` and `mybatis-plus` absent.

- [ ] **Step 5: Commit**

```bash
git add -A ag-ui .env.example
git commit -m "chore: remove MySQL and MyBatis persistence stack"
```

---

## Self-Review Notes

- Spec coverage: SessionLog → Task 4; catalog/display → Tasks 1–2; read model → Task 3; delete MySQL stack → Task 5; tests/verification → each task + Task 5 full run
- Review Focus items covered by DisplayViewStoreTest (corrupt/old seq), ReadModelStoreTest (reopen), Hitl/Agui tests (restart SessionLog), Task 5 grep/compile (orphan MyBatis)
- No MySQL data migration task (explicit non-goal)
