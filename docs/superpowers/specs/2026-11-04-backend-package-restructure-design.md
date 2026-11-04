# Backend Package Restructure Design

Date: 2026-11-04  
Status: Approved for planning  
Scope: `ag-ui` module Java packages under `com.beyondzy.demo`

## Goal

Reorganize backend packages so responsibilities are obvious:

- All HTTP Controllers live under `web`, grouped by feature.
- Persistence stays in `domain` + `mapper`.
- Runtime / application logic lives in clear feature modules.
- Eliminate top-level duplicate packages (same capability split across two trees).

This is a **move-only** refactor: package/import/path changes. No intentional behavior, HTTP path, or persistence schema changes.

## Decisions Locked

| Topic | Choice |
| --- | --- |
| Organizing principle | Hybrid: `web` + `domain`/`mapper` + feature runtime modules |
| Existing `service/` | Remove; fold into feature modules |
| `snapshot` / `projection` / `contract` | Split by duty: read-model types → `readmodel`; AG-UI protocol types → `agui` |
| `display` | Independent feature module |
| `web` layout | Subpackages by feature (`web.agui`, `web.readmodel`, `web.chat`, …) |
| Landing style | Flat feature modules (few inner subpackages; keep existing `impl` under each feature for Spring service implementations) |

## Target Package Tree

```
com.beyondzy.demo
├── ChatApplication
├── config/                  # Spring / MyBatis config only
├── domain/{user,session,display,readmodel}
├── mapper/{user,session,display,readmodel}
├── web/
│   ├── agui/                # AguiController only
│   ├── readmodel/           # ReadModelController
│   ├── chat/                # ChatController
│   └── user/                # only if/when a user HTTP API exists
├── session/                 # session runtime + former service.session
├── readmodel/               # read-model runtime + former snapshot/contract + service.readmodel
├── agui/                    # AguiService + AguiEventProjection
├── display/                 # display runtime + former service.display + DisplayViewStore
├── hitl/                    # HitlService (unchanged home)
├── user/                    # former service.user
├── tools/                   # agent tool implementations
└── support/                 # pure helpers (ContentValues, Payloads)
```

### Packages removed

- `service/`
- `snapshot/`
- `projection/`
- `contract/`
- Non-controller types under `web` (notably `web.agui.AguiService`)

## Class Placement Map

### web

| From | To |
| --- | --- |
| `web.agui.AguiController` | `web.agui.AguiController` (stay) |
| `web.ReadModelController` | `web.readmodel.ReadModelController` |
| `web.ChatController` | `web.chat.ChatController` |
| `web.agui.AguiService` | `agui.AguiService` |

### session

| From | To |
| --- | --- |
| `session.ChatSessions` | `session.ChatSessions` |
| `session.ThreadCatalog` | `session.ThreadCatalog` |
| `session.MySqlSessionLogStore` | `session.MySqlSessionLogStore` |
| `service.session.AgentSessionService` (+ `impl`) | `session.AgentSessionService` (+ `session.impl`) |
| `service.session.SessionObjectService` (+ `impl`) | `session.SessionObjectService` (+ `session.impl`) |
| `session.DisplayViewStore` | **move out** → `display.DisplayViewStore` |

### display

| From | To |
| --- | --- |
| `service.display.DisplayIndexService` (+ `impl`) | `display.DisplayIndexService` (+ `display.impl`) |
| `session.DisplayViewStore` | `display.DisplayViewStore` |

`domain.display.*` and `mapper.display.*` stay.

### readmodel

| From | To |
| --- | --- |
| `readmodel.ProjectionService` | `readmodel.ProjectionService` |
| `readmodel.ReadModelStore` | `readmodel.ReadModelStore` |
| `readmodel.CommandProjection` | `readmodel.CommandProjection` |
| `service.readmodel.*` (incl. `CommitBatch`, persistence iface/`impl`) | `readmodel.*` (+ `readmodel.impl` for the persistence impl) |
| `snapshot.ChatHistory` | `readmodel.ChatHistory` |
| `snapshot.ChatItems` | `readmodel.ChatItems` |
| `snapshot.ThreadViewAssembler` | `readmodel.ThreadViewAssembler` |
| `snapshot.IncrementalEventProjection` | `readmodel.IncrementalEventProjection` |
| `contract.ThreadView` | `readmodel.ThreadView` |

`domain.readmodel.*` and `mapper.readmodel.*` stay.

### agui

| From | To |
| --- | --- |
| `web.agui.AguiService` | `agui.AguiService` |
| `projection.AguiEventProjection` | `agui.AguiEventProjection` |

### hitl / user / tools / support / config / domain / mapper

- `hitl.HitlService` stays in `hitl` (may import `readmodel.ThreadView` after move).
- `service.user.*` → `user.*` (+ `user.impl` for the service impl).
- `tools/*`, `support/*`, `config/*`, `domain/*`, `mapper/*` stay in place unless a move above requires import updates only.

## Dependency Rules

Allowed:

```
web.*                → feature modules
feature modules      → domain, mapper, support, tools (as needed)
feature → feature    → directed only, no cycles
```

Typical edges:

- `agui` → `session`, `readmodel`
- `hitl` → `session`, `readmodel`
- `display` → `domain.display`, `mapper.display`
- `readmodel` → `session` only if already required by current code

Forbidden:

- feature modules → `web`
- `domain` / `mapper` / `support` → feature orchestration or `web`
- recreating top-level duplicates for the same capability

Hard rules:

1. Only Controllers (and optional web-only request/response DTOs) under `web.*`.
2. One top-level home per capability (`session`, `readmodel`, etc.).
3. No new abstraction layers for the sake of packaging.
4. No multi-module Maven split in this change.

## Test Layout

Mirror `main` packages under `src/test/java`:

- `web.agui.*Test` stays with web tests.
- Former `snapshot.*Test` → `readmodel.*Test`.
- `session.DisplayViewStoreTest` → `display.DisplayViewStoreTest`.
- Other tests move with the production types they cover.

## Migration Batches

Execute in order; after each batch: compile + run affected tests.

1. **Web intake** — move Controllers into feature subpackages; create `agui` and move `AguiService` out of `web`.
2. **Remove `service/`** — fold session/readmodel/display/user services into feature modules.
3. **Display gather** — move `DisplayViewStore` into `display`.
4. **Readmodel absorb** — move `snapshot/*` and `contract.ThreadView` into `readmodel`.
5. **Agui absorb** — move `AguiEventProjection` into `agui`.
6. **Tests** — mirror packages; run full `mvn test`.
7. **Cleanup** — delete empty packages; grep for stale FQCNs in code/docs; fix Spring/MyBatis references if any use old names.

## Acceptance Criteria

- Every `*Controller` lives under `com.beyondzy.demo.web.*`.
- Top-level packages `service`, `snapshot`, `projection`, `contract` no longer exist.
- No dual homes for the same capability (e.g. both `service.session` and runtime `session` services).
- `mvn test` passes.
- External HTTP routes and runtime behavior unchanged.

## Out of Scope

- Changing API contracts, event schemas, or DB tables.
- Splitting into multiple Maven modules.
- Rewriting business logic while moving classes.
- Frontend package changes.

## Risks

| Risk | Mitigation |
| --- | --- |
| Stale fully-qualified class names in config/docs | Global search for old package prefixes after each batch |
| Spring component scan surprises | Keep everything under `com.beyondzy.demo`; verify app context starts |
| Circular feature dependencies after moves | Preserve existing call graph; only relocate types |

## Non-Goals Recap

Do not introduce `persistence/` rename, deep inner layering (`feature.persistence.projection.view`), or vertical-slice absorption of `domain`/`mapper` into feature folders.
