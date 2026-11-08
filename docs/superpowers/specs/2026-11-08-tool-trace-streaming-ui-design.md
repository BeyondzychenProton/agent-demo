# Tool Trace + Streaming Soft Bubble UI

Date: 2026-11-08  
Status: approved for planning  
Scope: frontend presentation only (CopilotKit + existing thread store retained)

## Goal

Improve the visual experience of **tool-call progress** and **streaming intermediate states** without replacing the chat architecture. Keep CopilotKit input, the windowed transcript, and the thread store; restyle tool steps as a Trace timeline and messages as soft bubbles, optionally using `@assistant-ui/react` presentation pieces behind a thin display-only adapter.

## Decisions (from brainstorming)

| Topic | Choice |
|-------|--------|
| Priority | Both tool UX and streaming UX; **tool process first** |
| Tool visual | **A · Trace timeline** (thin step rows; details collapsed by default) |
| Streaming visual | **B · Soft bubble** (light surface; quiet “generating” chrome) |
| Integration approach | **Thin adapter**: keep `ViewItem` data path; new leaf UI components |
| Library | Install `@assistant-ui/react`; prefer Message/Tool primitives **without** handing over session ownership |
| Runtime | If primitives require a provider: **read-only** `useExternalStoreRuntime` projected from thread store; no assistant-ui composer / turn submission. If that fights virtualization: **degrade** to local Trace/Bubble that match the same visual spec |

## Non-goals

- Replacing CopilotKit or AG-UI transport
- Rewriting thread store / incremental sync / HITL APIs
- Switching the transcript to `ThreadPrimitive` (would break windowed virtualization)
- Merging multiple `ViewItem`s into a single turn DOM block in this iteration
- Theme / ThreadList redesign

## Current pain

- `TranscriptItem` wraps every row in a heavy bordered `article`
- `ToolCardView` reads like a debug panel (status pills, always-available param/result blocks)
- Streaming states surface loud copy (“正在生成 / 正在同步”) that competes with the answer

## Architecture

```
threadStore (ViewItem[])
  → WindowedTranscript (virtualizer — unchanged ownership)
    → TranscriptItem
         ├ message  → SoftMessageBubble
         └ tool-card → TraceToolStep (+ existing HITL / specialized result blocks)
```

Optional (best-effort):

```
view.items → convertMessage → useExternalStoreRuntime (read-only)
  → AssistantRuntimeProvider (display only)
```

Submission path remains:

```
CopilotChatInput → POST /api/sessions/:id/turns → threadStore
HITL → existing /interactions and /cancel endpoints
```

## Components

| Piece | Responsibility |
|-------|----------------|
| `SoftMessageBubble.tsx` | User/assistant soft bubble; `partial` caret / quiet status; weaker `localPhase` hierarchy |
| `TraceToolStep.tsx` | Timeline row: icon + title + duration; collapsible args/result/progress |
| `toolStatusMap.ts` | Map `ToolCardStatus` → visual state (`running` / `done` / `error` / `hitl` / …) |
| `ToolCardView.tsx` | Refactor to compose `TraceToolStep`; keep HITL, `OrderResult`, `write_note` hints, retry semantics |
| `WindowedTranscript.tsx` | Wire new leaves; reduce double-card chrome around tool rows |
| `AssistantDisplayProvider.tsx` (optional) | Read-only external store bridge; omit if primitives cannot sit cleanly inside the virtualizer |

### Tool status mapping

| `ToolCardStatus` | Trace behavior |
|------------------|----------------|
| `generating_arguments`, `requested`, `running` | Spinner; show progress when present |
| `completed` | Check; **collapsed by default** |
| `failed` | Error; **expanded by default** |
| `suspended` | Pause styling; **expanded** with HITL controls |
| `interrupted` | Muted; collapsed |

### Streaming message behavior

- Soft bubble: low-contrast fill, thin border (not the current thick debug card)
- `partial`: quiet generating indicator / caret
- Keep Markdown path via existing `CopilotChatAssistantMessage.MarkdownRenderer` when needed
- Do not change plain-text fast path heuristics unless required for layout

## Data flow & ownership

- **Source of truth**: existing thread store / server read model
- **UI**: one-way projection `ViewItem → presentation`
- No write-back from assistant-ui into the store
- Large content pagination (`ContentDetail`) unchanged
- Diagnostic / server view panels reuse the same leaf components where they render tool cards

## Error handling & degradation

- HITL busy / 409 / timeout / retry-same-decision behavior stays as today
- If `@assistant-ui/react` context cannot be used inside virtualized rows: ship local Trace/Bubble that still match Trace + soft-bubble specs; keep the dependency only if it still provides value (types/helpers), otherwise document removal in the plan
- Specialized result UIs (`lookup_order`, `write_note`) remain inside the expanded Trace body

## Acceptance criteria

1. A question that triggers tools shows Trace step rows; completed tools collapse by default
2. Streaming assistant text uses a soft bubble with quiet generating chrome
3. `suspended` tools auto-expand; approve / reject / cancel still work
4. Load-earlier history, large content pagination, and diagnostic view do not regress
5. Message send path remains CopilotKit input + `/turns` (not assistant-ui composer)

## Implementation order

1. Add `@assistant-ui/react`; spike read-only provider vs local-only leaves (decide degrade early)
2. `toolStatusMap` + `TraceToolStep`; refactor `ToolCardView`
3. `SoftMessageBubble`; update `TranscriptItem`
4. Polish streaming/HITL defaults; manual acceptance pass

## Testing

- Prefer existing frontend unit/e2e where present for HITL and transcript smoke
- Manual acceptance checklist above is the primary gate for visual work
- Avoid brittle screenshot assertions unless the repo already has that pattern
