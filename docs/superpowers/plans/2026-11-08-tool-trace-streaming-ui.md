# Tool Trace + Soft Bubble UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle tool-call progress as a Trace timeline and streaming messages as soft bubbles without changing CopilotKit, the thread store, or HITL APIs.

**Architecture:** Keep `WindowedTranscript` + `ViewItem` as the data path. Extract pure presentation maps, then swap leaf UI: `ToolCardView` composes `TraceToolStep`; messages render through `SoftMessageBubble`. `@assistant-ui/react` is installed for a short display-bridge spike; if primitives cannot sit cleanly inside the virtualizer, keep local leaves that match the Trace + soft-bubble visual spec and remove any unused dependency.

**Tech Stack:** React 19, Vite, Tailwind 4, existing CopilotKit v2 markdown renderer, Node test runner (`node --experimental-strip-types --test`), optional `@assistant-ui/react`.

**Spec:** `docs/superpowers/specs/2026-11-08-tool-trace-streaming-ui-design.md`

## Global Constraints

- CopilotKit input and `POST /api/sessions/:id/turns` remain the send path — not assistant-ui composer.
- Thread store / incremental sync / HITL endpoints stay unchanged.
- Do not switch the transcript list to `ThreadPrimitive` (windowed virtualization stays).
- Do not merge multiple `ViewItem`s into one turn DOM block in this iteration.
- HITL busy / 409 / timeout / retry-same-decision semantics and copy stay as today.
- `lookup_order` / `write_note` specialized result blocks remain inside the expanded tool body.
- `docs/` is gitignored; force-add plan/spec artifacts with `git add -f` when committing them.

## Review Focus

- Intermediate tool statuses (`generating_arguments` → `requested` → `running`) must all default to open/active chrome so the Trace row does not flicker closed mid-run — covered in Task 1.
- After history reload, `suspended` + `hitl.canRespond` must still render approve/reject/cancel — covered in Task 2 mapping + manual HITL step.
- `ServerViewPanel` / diagnostic transcript must pick up the same tool leaf (no duplicate old card chrome) — covered in Task 2 wiring check.
- Assistant `partial` chrome must stay quiet (no loud “正在同步” competing with text) while user bubbles stay distinct — covered in Task 3.
- Send path must remain CopilotKit + `/turns` after any assistant-ui install/spike — covered in Task 4.

## File map

| File | Responsibility |
|------|----------------|
| `frontend/src/components/chat/toolStatusMap.ts` | `ToolCardStatus` → visual state + `defaultOpen` |
| `frontend/src/components/chat/messageChrome.ts` | Quiet role/status labels for soft bubbles |
| `frontend/src/components/chat/TraceToolStep.tsx` | Collapsible Trace row shell |
| `frontend/src/components/chat/SoftMessageBubble.tsx` | Soft bubble shell for message rows |
| `frontend/src/components/ToolCardView.tsx` | Compose Trace + keep HITL / OrderResult / act() |
| `frontend/src/components/WindowedTranscript.tsx` | Wire SoftMessageBubble; reduce double-card chrome |
| `frontend/tests/toolStatusMap.test.ts` | Status → open/visual assertions |
| `frontend/tests/messageChrome.test.ts` | Quiet label assertions |
| `frontend/package.json` | Optional `@assistant-ui/react` add/remove from spike |

---

### Task 1: `toolStatusMap` presentation map

**Files:**
- Create: `frontend/src/components/chat/toolStatusMap.ts`
- Test: `frontend/tests/toolStatusMap.test.ts`

**Interfaces:**
- Consumes: `ToolCardStatus` from `frontend/src/types/chat.ts`
- Produces:
  - `export type TraceVisual = "pending" | "running" | "done" | "error" | "hitl" | "muted"`
  - `export type TracePresentation = { visual: TraceVisual; defaultOpen: boolean; label: string }`
  - `export function mapToolStatus(status: ToolCardStatus): TracePresentation`

Label strings (exact):

| status | label |
|--------|-------|
| `generating_arguments` | `接收参数` |
| `requested` | `已请求` |
| `running` | `执行中` |
| `completed` | `完成` |
| `failed` | `失败` |
| `suspended` | `待审批` |
| `interrupted` | `已中断` |

- [ ] **Step 1: Write the failing test**

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { mapToolStatus } from "../src/components/chat/toolStatusMap.ts";

test("completed collapses; failed and suspended expand", () => {
  assert.equal(mapToolStatus("completed").defaultOpen, false);
  assert.equal(mapToolStatus("failed").defaultOpen, true);
  assert.equal(mapToolStatus("suspended").defaultOpen, true);
});

test("intermediate statuses stay open and running/pending", () => {
  for (const status of ["generating_arguments", "requested", "running"] as const) {
    assert.equal(mapToolStatus(status).defaultOpen, true);
    assert.ok(["pending", "running"].includes(mapToolStatus(status).visual));
  }
});

test("interrupted is muted and collapsed", () => {
  assert.deepEqual(
    { visual: mapToolStatus("interrupted").visual, defaultOpen: mapToolStatus("interrupted").defaultOpen },
    { visual: "muted", defaultOpen: false },
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test --prefix frontend -- tests/toolStatusMap.test.ts`  
Expected: FAIL (module not found / `mapToolStatus` missing)

- [ ] **Step 3: Implement `mapToolStatus(status: ToolCardStatus): TracePresentation` in `frontend/src/components/chat/toolStatusMap.ts`**

Map visuals: intermediates → `pending` or `running` (`running` status → `running`); `completed` → `done`; `failed` → `error`; `suspended` → `hitl`; `interrupted` → `muted`. Use the label table above.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test --prefix frontend -- tests/toolStatusMap.test.ts`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/toolStatusMap.ts frontend/tests/toolStatusMap.test.ts
git commit -m "feat: map tool card status to trace presentation"
```

---

### Task 2: `TraceToolStep` + refactor `ToolCardView`

**Files:**
- Create: `frontend/src/components/chat/TraceToolStep.tsx`
- Modify: `frontend/src/components/ToolCardView.tsx`
- Test: extend `frontend/tests/toolStatusMap.test.ts` only if helpers move; otherwise rely on Task 1 + manual HITL check below

**Interfaces:**
- Consumes: `mapToolStatus` from Task 1; existing `ToolCardViewModel` / HITL `act()` behavior
- Produces:
  - `export function TraceToolStep(props: { title: string; status: ToolCardStatus; durationMs?: number; defaultOpen: boolean; children?: React.ReactNode }): JSX.Element`
  - `ToolCardView` still exports `function ToolCardView({ card, threadId, onChanged }: { card: ToolCardViewModel; threadId?: string | null; onChanged?: () => void }): JSX.Element`

- [ ] **Step 1: Implement `TraceToolStep` in `frontend/src/components/chat/TraceToolStep.tsx`**

Left-rail Trace row: status icon from `mapToolStatus(status).visual`, title, optional `durationMs`, collapsible body. Initial open state = `defaultOpen` prop (caller passes `mapToolStatus(card.status).defaultOpen`). Keep styling in existing dark champagne theme tokens (`border-border`, `text-muted-foreground`, etc.) — no new purple palette.

- [ ] **Step 2: Refactor `ToolCardView` to compose `TraceToolStep`**

Replace the current outer `<details>` chrome with `TraceToolStep`. Keep inside children: args, progress, result/`OrderResult`, error, `write_note` hint, HITL block + `act()`, meta ids. Do not change fetch URLs or retry rules.

- [ ] **Step 3: Confirm `ServerViewPanel` still imports `ToolCardView`**

No API change required; grep that diagnostic path still uses `ToolCardView` (not a forked old card).

- [ ] **Step 4: Manual HITL smoke**

Run frontend against a session that can suspend a tool. Expected: Trace row auto-expanded; 批准 / 拒绝 / 取消任务 still submit; 查询当前结果 still works.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/TraceToolStep.tsx frontend/src/components/ToolCardView.tsx
git commit -m "feat: render tool calls as collapsible trace steps"
```

---

### Task 3: `SoftMessageBubble` + transcript wiring

**Files:**
- Create: `frontend/src/components/chat/messageChrome.ts`
- Create: `frontend/src/components/chat/SoftMessageBubble.tsx`
- Modify: `frontend/src/components/WindowedTranscript.tsx` (`TranscriptItem`)
- Test: `frontend/tests/messageChrome.test.ts`

**Interfaces:**
- Consumes: message fields already on `ViewItem` (`role`, `text`, `partial`, `localPhase`)
- Produces:
  - `export function messageChrome(input: { role: "user" | "assistant" | "reasoning"; partial?: boolean; localPhase?: string | null }): { eyebrow: string; showCaret: boolean }`
  - `export function SoftMessageBubble(props: { role: "user" | "assistant" | "reasoning"; eyebrow: string; showCaret: boolean; children: React.ReactNode }): JSX.Element`

Eyebrow rules (exact):
- user → `用户` (+ ` · ${localPhaseLabel}` only when `localPhase` is set, using existing Chinese phase map in `TranscriptItem`)
- assistant + `partial` → `助手` (no ` · 正在生成` suffix; caret carries generating state)
- assistant + not partial → `助手`
- Do not put sync phrases like `正在同步` into the eyebrow

- [ ] **Step 1: Write the failing test**

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { messageChrome } from "../src/components/chat/messageChrome.ts";

test("partial assistant keeps quiet eyebrow and caret", () => {
  const chrome = messageChrome({ role: "assistant", partial: true });
  assert.equal(chrome.eyebrow, "助手");
  assert.equal(chrome.showCaret, true);
});

test("user eyebrow stays user-labeled", () => {
  assert.equal(messageChrome({ role: "user" }).eyebrow, "用户");
  assert.equal(messageChrome({ role: "user" }).showCaret, false);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test --prefix frontend -- tests/messageChrome.test.ts`  
Expected: FAIL

- [ ] **Step 3: Implement `messageChrome` and `SoftMessageBubble`**

Soft bubble: thin border + `bg-secondary/30` (or softer), rounded-2xl, no heavy debug card stacking. Optional caret element when `showCaret`.

- [ ] **Step 4: Wire `TranscriptItem`**

For `item.kind === "message"`, render `SoftMessageBubble` around existing plain / Markdown body. For tool rows, render `ToolCardView` **without** an extra outer bordered `article` wrapper (or make the wrapper borderless for tools) so Trace is not double-carded. Keep `ContentDetail` below.

- [ ] **Step 5: Run tests**

Run: `npm test --prefix frontend`  
Expected: existing + new unit tests PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/chat/messageChrome.ts frontend/src/components/chat/SoftMessageBubble.tsx frontend/src/components/WindowedTranscript.tsx frontend/tests/messageChrome.test.ts
git commit -m "feat: soft-bubble streaming message chrome"
```

---

### Task 4: `@assistant-ui/react` spike + acceptance

**Files:**
- Modify: `frontend/package.json` / lockfile as needed
- Optional create-then-delete: `frontend/src/components/chat/AssistantDisplayProvider.tsx`
- Modify: none of the send path files (`App.tsx` ChatInput must stay on `/turns`)

**Interfaces:**
- Consumes: Tasks 1–3 leaves
- Produces: either a retained read-only display bridge, or an explicit degrade decision with no unused dependency

- [ ] **Step 1: Install `@assistant-ui/react` in `frontend`**

Run: `npm install @assistant-ui/react --prefix frontend`

- [ ] **Step 2: Spike read-only bridge (time-box)**

Attempt `useExternalStoreRuntime` + `AssistantRuntimeProvider` as display-only around a single tool/message leaf. Do **not** wire composer `onNew` to replace `/turns`. If this fights the virtualizer or requires rewriting `WindowedTranscript`, stop and delete the provider file.

- [ ] **Step 3: Dependency cleanup**

If nothing from `@assistant-ui/react` remains imported, uninstall it. If something useful remains, keep it and document the import site in the commit message.

- [ ] **Step 4: Acceptance checklist (manual)**

1. Tool Trace rows appear; `completed` starts collapsed  
2. Soft bubble streaming with quiet generating chrome  
3. `suspended` auto-expands; HITL actions work  
4. Load-earlier + large content pagination still work  
5. Send path is still CopilotKit input + `/turns`

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/components/chat
git commit -m "chore: finish assistant-ui spike and UI acceptance pass"
```

---

## Self-review notes

- Spec coverage: Trace visual, soft bubble, thin adapter, HITL preserved, virtualization preserved, degrade path — all tasked.
- Optional `AssistantDisplayProvider` is explicitly time-boxed in Task 4, not required for Tasks 1–3 to ship value.
- No turn-merging / ThreadPrimitive / theme rewrite included (non-goals).
