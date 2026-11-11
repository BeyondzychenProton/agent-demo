# Agent Trace Progressive Disclosure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade tool, reasoning, and HITL-status rows in the chat transcript to three-level progressive disclosure with light-theme trace styling, while keeping `ApprovalBar` as the only place to approve/reject.

**Architecture:** In-place upgrade of `TraceToolStep` (shared L1/L2 shell), `ToolCardView` (L3 input/output fold), and reasoning via `SoftMessageBubble`/`messageChrome`. No backend protocol or `threadStore` kind changes. Suspended tools stay status-only rows; actions remain in `ApprovalBar`.

**Tech Stack:** React 19, TypeScript, Tailwind v4, Lucide icons, Node test runner (`frontend` package scripts).

**Spec:** `docs/superpowers/specs/2026-11-11-agent-trace-progressive-disclosure-design.md`

## Global Constraints

- Frontend only under `frontend/`; no AG-UI / Java / event-protocol changes
- Light theme only (adapt screenshot hierarchy; do not introduce dark-trace chrome)
- Per-step fold only (no run-level parent accordion)
- Timeline HITL never renders approve/reject/cancel controls
- Duration: ms in → `<1000` → `Nms`; `<60000` → one-decimal `Xs`; else `X分Y秒`; missing → hide
- L3 default closed; in-progress/failed default L2 open; completed/interrupted default L1 closed
- Prefer existing patterns in `frontend/src/components/chat/*`; keep virtualized measure path intact

## Review Focus

- Missing `durationMs` must hide the duration chip (never `0ms` / `0s`) — pinned in Task 1
- Suspended tool rows must not grow nested approval UI — pinned in Task 3
- Short reasoning text must not show a redundant L3 control — pinned in Task 4
- Huge tool args/results with `entry.details` must still defer to `ContentDetail` paging instead of dumping into L3 — pinned in Task 3
- Expanding L2/L3 must not break `WindowedTranscript` row measurement (no absolute-positioned overflow traps) — pinned in Task 5 smoke

---

## File map

| File | Responsibility |
|------|----------------|
| `frontend/src/components/chat/formatDuration.ts` | Pure duration formatter |
| `frontend/tests/formatDuration.test.ts` | Formatter unit tests |
| `frontend/src/components/chat/toolStatusMap.ts` | Status → visual/label/defaultOpen (L2 policy) |
| `frontend/tests/toolStatusMap.test.ts` | Label + defaultOpen assertions |
| `frontend/src/components/chat/TraceToolStep.tsx` | Shared L1/L2 disclosure shell |
| `frontend/src/components/ToolCardView.tsx` | Tool L2 summary + L3 detail fold; suspended status row |
| `frontend/src/components/chat/messageChrome.ts` | Reasoning L1 copy |
| `frontend/tests/messageChrome.test.ts` | Reasoning chrome assertions |
| `frontend/src/components/chat/SoftMessageBubble.tsx` | Reasoning L1/L2/L3 UI |
| `frontend/src/components/WindowedTranscript.tsx` | Wire reasoning through SoftMessageBubble only if props needed |
| `.gitignore` | Allow `docs/superpowers/plans/*.md` (already adjusted with this plan) |

---

### Task 1: `formatDuration`

**Files:**
- Create: `frontend/src/components/chat/formatDuration.ts`
- Test: `frontend/tests/formatDuration.test.ts`

**Interfaces:**
- Consumes: none
- Produces: `formatDuration(durationMs: number | undefined | null): string | null`

- [ ] **Step 1: Write the failing test**

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { formatDuration } from "../src/components/chat/formatDuration.ts";

test("hides missing duration", () => {
  assert.equal(formatDuration(undefined), null);
  assert.equal(formatDuration(null), null);
});

test("formats ms, seconds, and分秒", () => {
  assert.equal(formatDuration(840), "840ms");
  assert.equal(formatDuration(12400), "12.4s");
  assert.equal(formatDuration(541000), "9分1秒");
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- tests/formatDuration.test.ts`  
Expected: FAIL (module not found / export missing)

- [ ] **Step 3: Implement `formatDuration(durationMs: number | undefined | null): string | null`**

Exact thresholds from Global Constraints. Use `Math.floor` for minutes and remaining seconds when `>= 60000`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- tests/formatDuration.test.ts`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/formatDuration.ts frontend/tests/formatDuration.test.ts
git commit -m "feat: add trace duration formatter"
```

---

### Task 2: Status labels for L2 policy

**Files:**
- Modify: `frontend/src/components/chat/toolStatusMap.ts`
- Modify: `frontend/tests/toolStatusMap.test.ts`

**Interfaces:**
- Consumes: existing `ToolCardStatus`
- Produces: unchanged `mapToolStatus(status): TracePresentation` shape; labels become screenshot-like Chinese phrases

- [ ] **Step 1: Extend failing assertions for labels**

Add to `toolStatusMap.test.ts`:

```ts
test("labels match trace copy", () => {
  assert.equal(mapToolStatus("running").label, "执行中");
  assert.equal(mapToolStatus("suspended").label, "待审批");
  assert.equal(mapToolStatus("completed").label, "完成");
  assert.equal(mapToolStatus("failed").label, "失败");
  assert.equal(mapToolStatus("generating_arguments").label, "接收参数");
  assert.equal(mapToolStatus("requested").label, "已请求");
  assert.equal(mapToolStatus("interrupted").label, "已中断");
});
```

Keep existing defaultOpen tests (completed/interrupted closed; intermediate + failed + suspended open for map policy — suspended openness is unused by status-only row but leave map consistent).

- [ ] **Step 2: Run test to verify label failures if copy differs**

Run: `cd frontend && npm test -- tests/toolStatusMap.test.ts`  
Expected: FAIL only if current labels diverge; otherwise adjust Step 1 expected strings to the locked spec phrases and update implementation in Step 3.

- [ ] **Step 3: Update `LABELS` in `toolStatusMap.ts` to the asserted phrases**

- [ ] **Step 4: Run tests**

Run: `cd frontend && npm test -- tests/toolStatusMap.test.ts`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/toolStatusMap.ts frontend/tests/toolStatusMap.test.ts
git commit -m "feat: align tool status labels with trace copy"
```

---

### Task 3: `TraceToolStep` + `ToolCardView` L1/L2/L3

**Files:**
- Modify: `frontend/src/components/chat/TraceToolStep.tsx`
- Modify: `frontend/src/components/ToolCardView.tsx`
- Consumes: `formatDuration` from Task 1; `mapToolStatus` from Task 2

**Interfaces:**
- Consumes: `formatDuration(durationMs)`
- Produces:
  - `TraceToolStep({ title, status, durationMs?, defaultOpen, icon?, children? })`
  - `ToolCardView` L3 nested `<details>` with labels `输入` / `输出` (and error in output area when failed)
  - Suspended branch remains a non-interactive status row: `等待你确认 · {displayTitle}` — no nested Approval controls

- [ ] **Step 1: Upgrade `TraceToolStep` shell**

- Use `formatDuration` instead of raw `{durationMs}ms`
- Keep chevron + status icon; allow optional `icon` override for type glyph
- Summary line stays single-row L1/L2 header; `children` render only when open (L2 body)

- [ ] **Step 2: Upgrade `ToolCardView` disclosure**

- Non-suspended: wrap existing fields so progress/diff one-liners sit in L2 body; put arguments/result/error/`lookup_order` rich block/`write_note` hint inside a nested L3 `<details>` defaultClosed titled `明细`
- L3 panel: muted rounded block; sections `输入` then divider then `输出`
- If `card` is rendered beside `entry.details` from transcript, do not duplicate huge payloads when only a pointer exists — keep current Field/`ContentDetail` split (inline pretty JSON only when `card.arguments` / `card.result` already present on the view model)
- Suspended: keep status-only row (no `TraceToolStep` children, no buttons)

- [ ] **Step 3: Run unit suite**

Run: `cd frontend && npm test`  
Expected: PASS (formatDuration + toolStatusMap + existing tests)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/chat/TraceToolStep.tsx frontend/src/components/ToolCardView.tsx
git commit -m "feat: add L2/L3 disclosure to tool trace rows"
```

---

### Task 4: Reasoning trace row

**Files:**
- Modify: `frontend/src/components/chat/messageChrome.ts`
- Modify: `frontend/tests/messageChrome.test.ts`
- Modify: `frontend/src/components/chat/SoftMessageBubble.tsx`
- Modify: `frontend/src/components/WindowedTranscript.tsx` (only if SoftMessageBubble needs extra props)

**Interfaces:**
- Consumes: optional live elapsed only if already available on the item; otherwise omit duration
- Produces:
  - `messageChrome({ role: "reasoning", partial })` → eyebrow `深度思考中…` when partial, `已思考` when settled (or `深度思考` — keep one pair locked by the test below)
  - `SoftMessageBubble` reasoning: L1 summary row (accent), expand to L2 preview (~2 lines), L3 full body only when text exceeds preview

- [ ] **Step 1: Write failing chrome tests**

```ts
test("reasoning chrome uses thinking copy", () => {
  assert.equal(messageChrome({ role: "reasoning", partial: true }).eyebrow, "深度思考中…");
  assert.equal(messageChrome({ role: "reasoning", partial: true }).showCaret, true);
  assert.equal(messageChrome({ role: "reasoning", partial: false }).eyebrow, "已思考");
  assert.equal(messageChrome({ role: "reasoning", partial: false }).showCaret, false);
});
```

- [ ] **Step 2: Run to verify fail/pass gap**

Run: `cd frontend && npm test -- tests/messageChrome.test.ts`  
Expected: FAIL on eyebrow strings vs current `正在思考…` / `已思考`

- [ ] **Step 3: Update `messageChrome` + `SoftMessageBubble` reasoning layout**

- Match Trace visual language (chevron row, accent when partial)
- Preview: first ~2 lines / ~120 chars; if full text fits, skip L3 control
- Preserve streaming caret inside open body when `showCaret`

- [ ] **Step 4: Wire through `TranscriptItem` if props change**

Keep user/assistant paths unchanged.

- [ ] **Step 5: Run tests**

Run: `cd frontend && npm test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/chat/messageChrome.ts frontend/tests/messageChrome.test.ts frontend/src/components/chat/SoftMessageBubble.tsx frontend/src/components/WindowedTranscript.tsx
git commit -m "feat: render reasoning as progressive trace rows"
```

---

### Task 5: Manual smoke + docs hygiene

**Files:**
- Verify only (no feature code unless a smoke bugfix is required)
- Ensure plan/spec remain under allowed gitignore exceptions

- [ ] **Step 1: Build typecheck**

Run: `cd frontend && npm run build`  
Expected: `tsc --noEmit` and Vite build succeed

- [ ] **Step 2: Manual smoke checklist**

In `npm run dev` chat (or existing local stack):

1. Running tool shows L1/L2 open; L3 `明细` closed until clicked; duration formatted
2. Completed tool collapses to L1
3. Streaming reasoning shows accent L1; expand shows growing text
4. Suspended tool shows `等待你确认 · …` only; approve still only in bottom `ApprovalBar`
5. Expand/collapse near transcript bottom still stick-scrolls without jump/clip

- [ ] **Step 3: Commit any smoke fixes** (skip if none)

```bash
git add -A frontend
git commit -m "fix: polish trace disclosure after smoke"
```

---

## Self-review notes

- Spec coverage: duration formatter, labels, tool L1–L3, reasoning L1–L3, suspended status-only, ApprovalBar unchanged, light theme, no backend changes — each has a task.
- No run-level grouping / dark theme / inline approval tasks (explicitly out of scope).
- Interfaces stay small: one new pure helper; component prop additions optional `icon` only.
