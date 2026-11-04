# Backend Package Restructure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use executing-plans (or equivalent) to implement this plan task-by-task.

**Goal:** Move-only reorganize `ag-ui` Java packages so Controllers live under `web.*`, persistence stays in `domain`/`mapper`, and runtime logic sits in flat feature modules (`session`, `readmodel`, `agui`, `display`, `hitl`, `user`).

**Architecture:** Hybrid layout from approved design `docs/superpowers/specs/2026-11-04-backend-package-restructure-design.md`. Delete top-level `service`, `snapshot`, `projection`, `contract`. Keep Spring scan root `com.beyondzy.demo` and `@MapperScan("com.beyondzy.demo.mapper")` unchanged.

**Tech Stack:** Java, Spring Boot, Maven (`ag-ui/pom.xml`), JUnit tests under `ag-ui/src/test/java`.

**Working directory for commands:** `ag-ui/` unless noted.

---

## File Structure (target)

```
ag-ui/src/main/java/com/beyondzy/demo/
  web/{agui,readmodel,chat}/
  session/ (+ impl/)
  readmodel/ (+ impl/)
  agui/
  display/ (+ impl/)
  hitl/
  user/ (+ impl/)
  domain/, mapper/, config/, tools/, support/
```

Removed after completion: `service/`, `snapshot/`, `projection/`, `contract/`.

---

### Task 1: Web Controllers into feature subpackages

**Files:**
- Move: `web/ChatController.java` → `web/chat/ChatController.java`
- Move: `web/ReadModelController.java` → `web/readmodel/ReadModelController.java`
- Modify: package + imports in those two files
- Modify: any test/import referencing old FQCNs (grep)

**Steps:**
1. Create directories `web/chat` and `web/readmodel`.
2. Move the two controller files; set packages to `com.beyondzy.demo.web.chat` and `com.beyondzy.demo.web.readmodel`.
3. Leave `@RequestMapping` / route annotations untouched.
4. Grep and fix references:
   ```
   com.beyondzy.demo.web.ChatController
   com.beyondzy.demo.web.ReadModelController
   ```
5. Verify: `mvn -q -DskipTests compile`

**Done when:** Controllers compile under `web.chat` / `web.readmodel`; HTTP mappings unchanged.

---

### Task 2: Create `agui` module; move `AguiService` out of `web`

**Files:**
- Move: `web/agui/AguiService.java` → `agui/AguiService.java`
- Modify: `web/agui/AguiController.java` import of `AguiService`
- Modify: `src/test/java/.../readmodel/ReadModelTestServer.java` import
- Modify: any other `web.agui.AguiService` imports

**Steps:**
1. Create `agui/` under main java root.
2. Move `AguiService`; package → `com.beyondzy.demo.agui`.
3. Keep `@Service` (or current Spring stereotype) as-is.
4. Update `AguiController` and tests to import `com.beyondzy.demo.agui.AguiService`.
5. Verify: `mvn -q -Dtest=AguiQueuedRunTest test`

**Done when:** `web.agui` contains only `AguiController` (+ its test); `AguiService` lives in `agui`.

---

### Task 3: Fold `service.session` into `session`

**Files:**
- Move: `service/session/AgentSessionService.java` → `session/AgentSessionService.java`
- Move: `service/session/SessionObjectService.java` → `session/SessionObjectService.java`
- Move: `service/session/impl/*` → `session/impl/*`
- Modify: `session/MySqlSessionLogStore.java` imports
- Grep: `com.beyondzy.demo.service.session`

**Steps:**
1. Move interfaces + impls; packages → `com.beyondzy.demo.session` / `com.beyondzy.demo.session.impl`.
2. Fix imports project-wide.
3. Verify: `mvn -q -DskipTests compile`

**Done when:** No `service.session` references remain.

---

### Task 4: Fold `service.readmodel` into `readmodel`

**Files:**
- Move: `service/readmodel/ReadModelPersistenceService.java` → `readmodel/`
- Move: `service/readmodel/CommitBatch.java` → `readmodel/`
- Move: `service/readmodel/impl/ReadModelPersistenceServiceImpl.java` → `readmodel/impl/`
- Modify imports in: `readmodel/ReadModelStore.java`, `readmodel/ProjectionService.java`, `session/ChatSessions.java`, `config/ChatConfiguration.java`

**Steps:**
1. Move types; packages → `com.beyondzy.demo.readmodel` / `...readmodel.impl`.
2. Fix imports; leave bean method bodies unchanged.
3. Verify: `mvn -q -Dtest=ReadModelStoreTest,ProjectionServiceTest,CommandProjectionTest test`

**Done when:** No `service.readmodel` references remain.

---

### Task 5: Fold `service.display` into `display` + move `DisplayViewStore`

**Files:**
- Create: `display/` and `display/impl/`
- Move: `service/display/DisplayIndexService.java` → `display/`
- Move: `service/display/impl/DisplayIndexServiceImpl.java` → `display/impl/`
- Move: `session/DisplayViewStore.java` → `display/DisplayViewStore.java`
- Move test: `session/DisplayViewStoreTest.java` → `display/DisplayViewStoreTest.java`
- Modify imports in: `session/ChatSessions.java`, `session/ThreadCatalog.java`, `config/ChatConfiguration.java`, display-related tests

**Steps:**
1. Move production + test types; packages → `com.beyondzy.demo.display` / `...display.impl`.
2. Grep `service.display` and `session.DisplayViewStore`.
3. Verify: `mvn -q -Dtest=DisplayViewStoreTest,IncrementalDisplayTest,ThreadCatalogTest test`

**Done when:** `display` owns index service + view store; `service.display` gone.

---

### Task 6: Fold `service.user` into `user`

**Files:**
- Move: `service/user/UserService.java` → `user/UserService.java`
- Move: `service/user/impl/UserServiceImpl.java` → `user/impl/UserServiceImpl.java`
- Grep: `com.beyondzy.demo.service.user`

**Steps:**
1. Move + fix packages/imports.
2. Delete empty `service/` tree if nothing left.
3. Verify: `mvn -q -DskipTests compile` and grep shows zero `com.beyondzy.demo.service`

**Done when:** Top-level `service/` package directory is gone.

---

### Task 7: Absorb `snapshot` + `contract.ThreadView` into `readmodel`

**Files:**
- Move: `snapshot/ChatHistory.java` → `readmodel/`
- Move: `snapshot/ChatItems.java` → `readmodel/`
- Move: `snapshot/ThreadViewAssembler.java` → `readmodel/`
- Move: `snapshot/IncrementalEventProjection.java` → `readmodel/`
- Move: `contract/ThreadView.java` → `readmodel/ThreadView.java`
- Move tests: `snapshot/ThreadViewTest.java`, `snapshot/IncrementalProjectionTest.java` → `readmodel/`
- Modify all imports of `com.beyondzy.demo.snapshot.*` and `com.beyondzy.demo.contract.ThreadView` (notably `hitl/HitlService.java`, `web/chat/ChatController.java`, `session/ChatSessions.java`, multiple tests)

**Steps:**
1. Move types; packages → `com.beyondzy.demo.readmodel`.
2. Update cross-module imports (`hitl` → `readmodel.ThreadView` is expected and allowed).
3. Delete empty `snapshot/` and `contract/`.
4. Verify: `mvn -q -Dtest=ThreadViewTest,IncrementalProjectionTest,ProjectionServiceTest,HitlIntegrationTest test`

**Done when:** No `snapshot` or `contract` packages remain.

---

### Task 8: Absorb `projection.AguiEventProjection` into `agui`

**Files:**
- Move: `projection/AguiEventProjection.java` → `agui/AguiEventProjection.java`
- Move test: `projection/ToolProjectionTest.java` → `agui/ToolProjectionTest.java`
- Modify: `agui/AguiService.java` import

**Steps:**
1. Move + package → `com.beyondzy.demo.agui`.
2. Grep `com.beyondzy.demo.projection`.
3. Delete empty `projection/`.
4. Verify: `mvn -q -Dtest=ToolProjectionTest,AguiQueuedRunTest test`

**Done when:** No `projection` package remains; AG-UI protocol types live only under `agui`.

---

### Task 9: Full verification + stale reference sweep

**Files:** possibly docs under `docs/integration/` if they cite old Java packages (optional / best-effort; `docs/` may be gitignored).

**Steps:**
1. Grep for forbidden leftovers:
   ```
   com.beyondzy.demo.service
   com.beyondzy.demo.snapshot
   com.beyondzy.demo.projection
   com.beyondzy.demo.contract
   ```
2. Confirm every `*Controller.java` path is under `.../web/`.
3. Run full suite: `mvn test`
4. Spot-check acceptance criteria from the design doc.

**Done when:** Grep clean (code), `mvn test` green, acceptance criteria satisfied.

---

## Execution Notes

- Prefer IDE “Move class” / `git mv` so history is preserved; always update `package` declaration.
- Do **not** change method bodies, mappings, SQL, or bean wiring logic except import/FQCN updates.
- Keep `impl` subpackages for Spring service implementations only.
- After each task, if compile fails, fix imports before starting the next task.
- Commit cadence: one commit per task (or per 1–2 tasks) is fine if the user asks for commits; otherwise leave commits to the user.

## Plan Status

- [ ] Task 1 — web controller subpackages
- [ ] Task 2 — AguiService → agui
- [ ] Task 3 — service.session → session
- [ ] Task 4 — service.readmodel → readmodel
- [ ] Task 5 — display module gather
- [ ] Task 6 — service.user → user; delete service/
- [ ] Task 7 — snapshot + ThreadView → readmodel
- [ ] Task 8 — AguiEventProjection → agui
- [ ] Task 9 — full test + cleanup
