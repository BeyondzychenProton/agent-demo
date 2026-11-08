# ChatGPT-light Shell + ApprovalBar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a light ChatGPT-like shell, move HITL to an oldest-first ApprovalBar above the composer, summarize suspended tools in the transcript, and remove diagnostic / 回到最新 / PendingInbox chrome.

**Architecture:** Keep CopilotKit + thread store + windowed transcript. Swap CSS tokens to a light palette; add `ApprovalBar` that loads the oldest actionable pending interaction; strip inline HITL controls from suspended `ToolCardView` rows.

**Tech Stack:** React 19, Vite, Tailwind 4, existing HITL HTTP endpoints, Node test runner.

**Spec:** `docs/superpowers/specs/2026-11-08-chatgpt-light-hitl-shell-design.md`

## Global Constraints

- Do not change `/turns`, HITL answer/cancel URLs, or busy/409/retry-same-decision semantics.
- Do not loosen `submissionBlocked` / pending locks while approvals remain.
- Main shell must not expose diagnostic dual-pane, 「回到最新」, or top PendingInbox.
- Accent color is for approval actions (and focus), not decorative chrome.
- `docs/` is gitignored — `git add -f` for plan/spec commits.

## Review Focus

- Oldest-first selection must ignore non-respondable items and advance after refresh — Task 2 tests.
- Suspended stream rows must not still render 批准/拒绝/取消 — Task 3.
- Light tokens must apply to sidebar + main pane (not only transcript) — Task 1.
- Composer still blocked when `pendingCount > 0` — Task 2 wiring / manual check.
- Stick-to-bottom from prior fix must keep working after toolbar removals — Task 4.

## File map

| File | Responsibility |
|------|----------------|
| `frontend/src/styles.css` | Light ChatGPT tokens; quiet `glass-panel` |
| `frontend/src/components/chat/SoftMessageBubble.tsx` | Light user/assistant bubbles; caret without champagne |
| `frontend/src/components/chat/TraceToolStep.tsx` | Light-friendly status colors |
| `frontend/src/App.tsx` | Remove diagnostic/PendingInbox; mount ApprovalBar |
| `frontend/src/components/WindowedTranscript.tsx` | Remove 「回到最新」; light status text colors |
| `frontend/src/components/ThreadListPanel.tsx` | Drop champagne-active chrome for light neutrals |
| `frontend/src/components/chat/pickOldestPending.ts` | Pure oldest actionable picker |
| `frontend/src/components/ApprovalBar.tsx` | Fixed bar UI + HITL act |
| `frontend/src/components/ToolCardView.tsx` | Suspended → summary only |
| `frontend/tests/pickOldestPending.test.ts` | Oldest-first selection |
| `frontend/tests/e2e/readmodel.spec.ts` | HITL flow targets ApprovalBar, not inbox details |

---

### Task 1: Light theme + remove shell chrome

**Files:**
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/components/chat/SoftMessageBubble.tsx`
- Modify: `frontend/src/components/chat/TraceToolStep.tsx`
- Modify: `frontend/src/App.tsx` (remove diagnostic state/UI only in this task if ApprovalBar not ready — leave a placeholder slot comment OR keep PendingInbox until Task 2; prefer remove diagnostic + 回到最新 here, defer PendingInbox deletion to Task 2 when ApprovalBar lands)
- Modify: `frontend/src/components/WindowedTranscript.tsx` (remove 「回到最新」 button)
- Modify: `frontend/src/components/ThreadListPanel.tsx` (active thread styles)
- Modify: `frontend/src/auth/LoginPage.tsx` (error/text colors that assume dark amber)

**Interfaces:**
- Consumes: existing Tailwind semantic tokens (`background`, `foreground`, `secondary`, `primary`, …)
- Produces: light `:root` values; no new public TS API

Light token targets (exact enough to implement):

| Token | Direction |
|-------|-----------|
| `--background` | near white `oklch(0.99 0.005 95)` |
| `--foreground` | near black gray `oklch(0.25 0.01 255)` |
| `--secondary` | light gray fill for user bubble |
| `--primary` | solid accent for Approve (blue-leaning OK; not purple-on-white cliché — prefer `oklch(0.45 0.14 250)` range) |
| `--border` | soft gray |
| body background | flat `var(--background)` — remove aurora gradients |
| `.glass-panel` | white/98% card, 1px border, light shadow — no blur glow |

- [ ] **Step 1: Update `styles.css` tokens and body / glass-panel**

- [ ] **Step 2: Soften SoftMessageBubble + TraceToolStep + ThreadListPanel + LoginPage contrast for light**

User bubble: `bg-secondary`; assistant: transparent / no fill; caret: `bg-foreground/50` (not champagne).

- [ ] **Step 3: Remove diagnostic toggle and dual column from `App.tsx`; remove 「回到最新」 from `WindowedTranscript`**

Keep stick-to-bottom logic. Leave PendingInbox until Task 2.

- [ ] **Step 4: Verify build**

Run: `npm run build --prefix frontend`  
Expected: success

- [ ] **Step 5: Commit**

```bash
git add frontend/src/styles.css frontend/src/components/chat/SoftMessageBubble.tsx frontend/src/components/chat/TraceToolStep.tsx frontend/src/App.tsx frontend/src/components/WindowedTranscript.tsx frontend/src/components/ThreadListPanel.tsx frontend/src/auth/LoginPage.tsx
git commit -m "feat: switch chat shell to light ChatGPT-like theme"
```

---

### Task 2: `pickOldestPending` + `ApprovalBar`

**Files:**
- Create: `frontend/src/components/chat/pickOldestPending.ts`
- Create: `frontend/src/components/ApprovalBar.tsx`
- Modify: `frontend/src/App.tsx` (mount bar above ChatInput; delete `PendingInbox`)
- Test: `frontend/tests/pickOldestPending.test.ts`
- Modify: `frontend/tests/e2e/readmodel.spec.ts` (HITL clicks ApprovalBar)

**Interfaces:**
- Consumes: `ViewItem` from thread store; HITL fields on tool-card items
- Produces:
  - `export function pickOldestPending(items: ViewItem[]): ViewItem | null` — first tool-card with `item.status === "suspended"` and `item.hitl?.canRespond === true` in array order (API returns oldest-first page)
  - `export function ApprovalBar({ threadId }: { threadId: string }): JSX.Element | null`

HITL act behavior must match `ToolCardView` (same paths, timeout 15s, 409/500/retry rules). After successful accept of response, call `getThreadStore(threadId).refresh()` (and refetch interactions).

Remaining count copy when `pendingCount > 1`: `还有 ${pendingCount - 1} 条待确认`.

- [ ] **Step 1: Write failing tests for `pickOldestPending`**

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { pickOldestPending } from "../src/components/chat/pickOldestPending.ts";
import type { ViewItem } from "../src/readmodel/threadStore.ts";

function tool(id: string, canRespond: boolean, status: "suspended" | "completed" = "suspended"): ViewItem {
  return {
    itemId: id, orderKey: 1, contentVersion: 1, details: {},
    item: {
      kind: "tool-card", toolCallId: id, turnId: "t", runId: "r", name: "write_note",
      displayTitle: id, status,
      hitl: canRespond
        ? { requestId: "req-" + id, askPrompt: "确认？", decision: null, canRespond: true }
        : { requestId: "req-" + id, askPrompt: "确认？", decision: null, canRespond: false },
    },
  };
}

test("picks first suspended canRespond item", () => {
  const picked = pickOldestPending([
    tool("a", false), tool("b", true), tool("c", true),
  ]);
  assert.equal(picked?.itemId, "b");
});

test("returns null when none actionable", () => {
  assert.equal(pickOldestPending([tool("a", false), tool("b", false, "completed")]), null);
});
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `npm test --prefix frontend -- tests/pickOldestPending.test.ts`

- [ ] **Step 3: Implement `pickOldestPending` and `ApprovalBar`**

ApprovalBar fetches `/api/v2/threads/:id/interactions?after=0` when `pendingCount > 0`, runs `pickOldestPending` on `data.items`, renders prompt + reason + 批准/拒绝/取消任务. Hide when none.

- [ ] **Step 4: Wire App — ApprovalBar above ChatInput; delete PendingInbox**

- [ ] **Step 5: Update e2e HITL test to target ApprovalBar**

Region name: `审批` (set `aria-label="审批"` on the bar). Click 拒绝 without toggling a details summary.

- [ ] **Step 6: Run unit tests**

Run: `npm test --prefix frontend`  
Expected: PASS including new tests

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/chat/pickOldestPending.ts frontend/src/components/ApprovalBar.tsx frontend/src/App.tsx frontend/tests/pickOldestPending.test.ts frontend/tests/e2e/readmodel.spec.ts
git commit -m "feat: add oldest-first ApprovalBar above composer"
```

---

### Task 3: Suspended tool summary in transcript

**Files:**
- Modify: `frontend/src/components/ToolCardView.tsx`
- Test: extend `frontend/tests/toolStatusMap.test.ts` only if a pure helper is extracted; otherwise manual + e2e coverage

**Interfaces:**
- Consumes: `ToolCardViewModel`
- Produces: when `card.status === "suspended"`, render summary row only — exact eyebrow text prefix `等待你确认` and `card.displayTitle`

- [ ] **Step 1: Change `ToolCardView` suspended branch**

If suspended: render a quiet line (can reuse Trace chrome without HITL children): `等待你确认 · {displayTitle}`. No reason input, no 批准/拒绝/取消任务, no hitl panel.

- [ ] **Step 2: Grep that 批准 only remains under ApprovalBar for main path**

Run: `rg "批准" frontend/src` — expect matches in `ApprovalBar.tsx` (and possibly legacy ServerViewPanel). Stream ToolCardView must not include 批准 for suspended.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ToolCardView.tsx
git commit -m "feat: summarize suspended tools awaiting ApprovalBar"
```

---

### Task 4: Acceptance pass

**Files:** none required unless bugs found

- [ ] **Step 1: Manual checklist**

1. Light white/gray shell  
2. ApprovalBar oldest-first; advances  
3. Suspended row is summary-only  
4. No 服务端视图 / 回到最新 / 待处理交互 header  
5. `/turns` send path unchanged; streaming still sticks to bottom  

- [ ] **Step 2: `npm test --prefix frontend` and `npm run build --prefix frontend`**

- [ ] **Step 3: Commit only if fixes landed; otherwise note clean in ledger**

---

## Self-review notes

- Spec coverage: theme, ApprovalBar, suspended summary, removals, API stability — tasked.
- PendingInbox removal is gated on ApprovalBar (Task 2) so HITL never has zero UI mid-migration.
- E2e update prevents the prior “click summary collapses open row” class of failure against the new bar.
