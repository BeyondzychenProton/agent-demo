# ChatGPT-light shell + bottom ApprovalBar

Date: 2026-11-08  
Status: approved for planning  
Depends on: Trace + soft-bubble leaves (`2026-11-08-tool-trace-streaming-ui-design.md`)

## Goal

Restyle the windowed chat shell to a **light ChatGPT-like** look, move human approval to a **fixed bar above the composer** (oldest pending first), show only a short “waiting for confirmation” summary on suspended tools in the transcript, and remove noisy chrome (`服务端视图`, `回到最新`, top `PendingInbox`).

## Decisions

| Topic | Choice |
|-------|--------|
| Visual | Light ChatGPT: white canvas, light-gray user bubble, quiet assistant text; accent only on approval actions |
| HITL placement | Fixed `ApprovalBar` above input; stream shows summary only |
| Multiple pending | Process oldest one; after resolve, auto-advance to next |
| Approach | Thin remodel — keep CopilotKit, thread store, virtual transcript, HITL APIs |

## Non-goals

- Rewriting thread store / SSE / `/turns` / HITL endpoints
- Full ChatGPT clone of sidebar IA
- Reintroducing diagnostic dual-pane in the main shell
- Dark theme toggle in this iteration

## Visual system

Replace dark champagne/aurora tokens in `frontend/src/styles.css` (and shell class usage) with a light palette:

- Page / panels: near-white background, soft gray borders, minimal shadow (no glass-panel glow)
- User bubble: light gray fill
- Assistant: no heavy card; inherit Trace/soft-bubble structure with light borders
- Sidebar: light surface consistent with main pane
- Primary accent: used for **Approve** (and focus ring); Reject secondary; Cancel text/ghost

Copy and layout stay Chinese where the product already is.

## HITL: ApprovalBar

**Placement:** Directly above `CopilotChatInput` in `WindowedApp`.

**Data:** Fetch oldest actionable pending via existing  
`GET /api/v2/threads/:id/interactions?after=0` (same source as today’s `PendingInbox`). Prefer the first item with `hitl.canRespond` (or equivalent pending interaction that can be answered).

**UI:**
- Title / ask prompt from the pending interaction
- Optional short tool name / display title
- Optional reason field (same semantics as today’s HITL form)
- Actions: 批准 / 拒绝 / 取消任务 — same endpoints and busy/409/retry-same-decision behavior as `ToolCardView.act`
- If `pendingCount > 1`, quiet text like “还有 N-1 条待确认”
- On success → refresh → bar shows the next oldest (or hides when none)

**While bar is active:** composer may stay blocked as today when `pendingCount > 0` / `submissionBlocked` (do not loosen locks).

## Transcript: suspended tools

For `status === "suspended"` (awaiting HITL):

- Render a compact Trace/summary line: **等待你确认** · `{displayTitle}` (or ask prompt snippet)
- Do **not** render inline approve/reject/cancel, reason input, or the full HITL panel in the stream
- Args/result remain collapsed behind Trace unless already required for non-HITL states
- Completed / failed / running Trace behavior from the prior design stays

`ServerViewPanel` / legacy paths: if still reachable, reuse the same summary rule for suspended cards so chrome does not diverge; diagnostic toggle is removed from the main shell only.

## Removals

From the main windowed shell (`App.tsx` / `WindowedTranscript` toolbar):

1. 「打开/关闭服务端视图」 and diagnostic second column
2. 「回到最新」 button (stick-to-bottom follow remains)
3. Top `PendingInbox` region (ApprovalBar replaces it)

Keep other useful controls (e.g. load earlier history) unless they become redundant.

## Acceptance

1. Light shell: white/gray ChatGPT-like; no dark champagne background as default
2. Pending HITL shows ApprovalBar above input; oldest first; advances after resolve
3. Suspended tool in transcript is summary-only (no inline HITL controls)
4. 服务端视图 / 回到最新 / 待处理交互 header UI gone from main shell
5. Approve / reject / cancel still hit the same APIs; send path still CopilotKit + `/turns`

## Implementation order

1. Light theme tokens + shell layout cleanup (remove diagnostic / 回到最新 / PendingInbox)
2. `ApprovalBar` + wire above composer; oldest-first fetch/advance
3. Suspended tool summary in `ToolCardView` / Trace path
4. Manual acceptance pass (HITL + streaming stick-to-bottom still works)
