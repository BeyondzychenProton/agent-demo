# Agent 执行轨迹三级递进展示 — Design Spec

**Date:** 2026-11-11  
**Status:** Approved for planning  
**Scope:** Frontend only (`frontend/`)

## Goal

优化 agent 执行过程中的实时渲染：工具调用、深度思考、人工审批状态，采用与参考截图一致的**三级递进展开**，视觉为**浅色适配**（保留现有产品配色，统一截图中的层级与图标语言）。

## Decisions (locked)

| Topic | Choice |
|-------|--------|
| Level 1 grouping | **Per-step** — each tool / reasoning / HITL status row folds independently |
| Visual theme | **Light adapt** — hierarchy/icons from screenshots; keep current light chat chrome |
| Approval actions | **Keep bottom `ApprovalBar`** — timeline shows status only, no inline approve/reject |
| Implementation | **In-place upgrade** of existing components (no new run-level timeline model) |

## Out of scope

- Full-app dark theme
- Parent fold grouping by entire run/turn
- Inline approval buttons inside the transcript
- Backend event / view-model protocol changes

## Architecture

```
WindowedTranscript / TranscriptItem
  ├─ message (user/assistant)     → SoftMessageBubble (unchanged behavior)
  ├─ message (reasoning)          → Trace-styled row via SoftMessageBubble upgrade
  └─ tool-card                    → ToolCardView
        └─ TraceToolStep (L1/L2 shell)
              └─ L2 summary + L3 detail fold

ApprovalBar (input area)          → unchanged placement & API
```

No change to `threadStore` item kinds or incremental event types.

## Progressive disclosure

### Tool call (`ToolCardView` + `TraceToolStep`)

| Level | Content |
|-------|---------|
| **L1** | Chevron + type icon + dynamic label (`正在运行命令` / `工具调用` · summary) + elapsed time + status |
| **L2** | Expanded header with tool display name; progress / short status line when present |
| **L3** | Nested “明细” fold: **输入** (arguments JSON) and **输出** (result / error), muted rounded panel |

`suspended` tools: L1 status row only (`等待你确认 · {displayTitle}`); do not expand into action buttons.

### Reasoning (`role === "reasoning"`)

| Level | Content |
|-------|---------|
| **L1** | Accent-colored summary: `深度思考中，用时 …` while `partial`; settled label when complete |
| **L2** | Short preview (~2 lines) when expanded |
| **L3** | Full reasoning body (scrollable); streaming caret while `partial`. If the full text already fits in the L2 preview, omit a separate L3 control. |

### HITL (timeline)

- Show pending/decided status in the tool row style.
- All approve / reject / cancel interactions remain in `ApprovalBar`.

### Default open policy (live)

- In-progress or failed → default open to **L2**
- Completed / interrupted → default collapsed to **L1**
- **L3** always user-opt-in (default closed)
- Reasoning while streaming: L1 shows live elapsed; if open, body grows in place

## Visual language (light)

- Dense vertical rows, thin separators, left chevron
- Small monochrome/type icons (tool / thinking / pause)
- Status colors reuse existing map: running amber, done green, error red, HITL/primary blue, thinking accent blue
- Duration formatter (single rule): input is milliseconds when available. `< 1000` → `Nms`; `< 60_000` → one-decimal seconds (`12.4s`); otherwise `X分Y秒`. Missing value → hide duration (never show `0`).
- L3 panels: rounded, `muted` background, small uppercase-ish labels `输入` / `输出`
- Large payloads continue to use existing `ContentDetail` paging — do not dump huge JSON into L3 DOM by default when eviction/details refs exist

## Component changes

1. **`TraceToolStep`** — generalize as shared L1/L2 shell (icon slot, label, duration formatter, chevron).
2. **`ToolCardView`** — wire L2 summary + L3 input/output fold; simplify suspended to status row.
3. **`SoftMessageBubble` / `messageChrome`** — reasoning path matches Trace row semantics and duration.
4. **`toolStatusMap`** — adjust labels to screenshot-like Chinese phrases where needed (`执行中`, `待审批`, etc.).
5. **`ApprovalBar`** — no relocation; ensure copy still aligns with timeline status wording.
6. **`WindowedTranscript`** — minimal wiring only if reasoning needs duration props from item timestamps (if unavailable, omit duration rather than invent backend fields).

## Error & edge handling

- Missing `durationMs` / timestamps: hide duration rather than show `0`.
- Failed tools: default L2 open; error text visible in L2 or L3 output section.
- Interrupted: muted status, default L1 collapsed.
- `lookup_order` rich result and `write_note` file hint remain inside L3 (or L2 one-liner), not a separate card chrome.
- Virtualized list: expanding L2/L3 must still measure correctly via existing `measureElement`.

## Testing

- Update / add unit tests for status label mapping and any duration formatter helper.
- Keep existing ApprovalBar / pickOldestPending behavior covered.
- Smoke: streaming reasoning + running tool + suspended tool still render without layout jump regressions in manual check.

## Success criteria

1. User can scan L1 rows for “what is happening / how long”.
2. One click reveals L2 context; a second disclosure reveals L3 raw detail.
3. Approval actions remain only in the bottom bar.
4. Light theme stays consistent with the rest of the chat UI.
5. No backend contract changes required to ship.
