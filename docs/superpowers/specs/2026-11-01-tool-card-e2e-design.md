# 工具卡片端到端（D1=B）设计

日期：2026-11-01
状态：已确认，进入实现
范围：大纲 3.3 / 3.5 / 6.1.2 / 6.1.6 / 6.1.7 / 6.1.8 / 6.1.9 的最小可运行验证

## 1. 目标

在同一份 SessionLog 事实之上，同时验证两条渲染路径：

- live A：`SessionEvent → M3 投影 → AG-UI 帧 → CopilotChat + useRenderTool`
- history B：`SessionEvent → M4 聚合 → SnapshotResponse + IncrementalEvent → 服务端视图面板`

两条路径最终产出同一个 `ToolCardViewModel`，刷新后卡片不丢、字段一致。

## 2. 非目标

HITL、状态面板/A2UI、eviction 外置、Redis/JDBC/OSS、鉴权、409 单 run 锁、run 级裁剪。
本轮不新增任何业务表；现有 `ThreadCatalog` 的 SQLite 读索引保持原样。

## 3. 工具

| 工具 | 行为 | 卡片 |
|---|---|---|
| `lookup_order(order_id)` | 内存订单表 A1001/A1002，执行中发进度 | 前端注册定制订单卡 |
| `search_docs(query)` | 固定文档集 | 未注册，走默认折叠块 |
| `write_note(path, content)` | 仅允许写 workspace/notes，返回 added/deleted | 通用卡 + diffStats |

工具注册：`@Tool` + `@ToolParam`，`Toolkit.registerTool(...)`，交给 `HarnessAgent.builder().toolkit(...)`。
`ToolCatalog` 维护工具名 → 折叠标题模板、是否定制卡片、是否文件类。

## 4. 后端模块

- `demo/tools/*`：三个工具 + `ToolCatalog`。
- `demo/projection/AguiEventProjection`：M3，每个 run 一个实例，输出文本与工具帧；保证 start/end 配对，`run/end` 先闭合再 `RUN_FINISHED`。
- `demo/snapshot/ThreadViewAssembler`：M4，`ChatItems` → 6.1.8 `SnapshotResponse`。
- `demo/snapshot/IncrementalEventProjection`：committed 事件 → 5 类 `IncrementalEvent`。
- `ChatItems`：补充 startedAt/endedAt、原始参数、结果、diffStats、displayTitle。
- `ChatController`：新增 `GET /api/threads/{id}/view` 与 `GET /api/threads/{id}/view/events?after=`。

## 5. 事件映射

### 5.1 live 帧

| SessionEvent | AG-UI |
|---|---|
| `model/chunk` 文本 | `TEXT_MESSAGE_STARTED` / `TEXT_MESSAGE_CONTENT` |
| `model/chunk` 工具分片 | `TOOL_CALL_STARTED`（首次）+ `TOOL_CALL_ARGS`（delta） |
| `tool/requested` | 幂等补 `TOOL_CALL_STARTED`，不重复 args |
| `tool/dispatch` | 仅在尚未发过参数时补发完整参数 |
| `tool/chunk` | `CUSTOM(name=tool-progress)` |
| `action/end` / `tool/result` | `TOOL_CALL_END` + `TOOL_CALL_RESULT` |
| `run/end` | 闭合 open text/tool → `RUN_FINISHED` |

### 5.2 增量事件

`message-delta`、`card-args`、`card-status`、`card-result`、`run-finished`；HITL 两类保留类型不触发。

## 6. 前端

- `src/types/chat.ts`：6.1.8 契约 + `upsertChatItem`。
- `src/components/ToolCardView.tsx`：唯一卡片组件；订单卡为子视图。
- `src/components/ServerViewPanel.tsx`：快照 + SSE 增量，展示 `asOfSeq/cursor/activeRun`。
- `App.tsx`：三栏布局；注册 `useRenderTool({ name: "lookup_order" })`。

## 7. 验收

1. 「查一下订单 A1001」→ 实时出现订单卡，状态流转到 completed。
2. 右侧面板出现同构卡片；刷新后仍在。
3. 「搜一下 AgentScope 的流程」→ 默认折叠块。
4. 「把结论写到 notes/compare.md」→ 显示 +N/-M。
5. 重启后端 → 面板内容保留。
6. `mvn test`、`npm run build` 通过。

## 8. 已知边界（写入验证结论）

1. AG-UI 标准帧没有工具进度帧，`tool/chunk` 只能落 `CUSTOM`；CopilotKit v2 `useRenderTool` 只暴露 inProgress/executing/complete，不暴露 progress。
2. 现有 `ThreadCatalog` 的 4 张 SQLite 表属于可重建读索引，但性质上是元数据表 + 页面模型表，与大纲「不建任何业务表」存在取舍，本轮不修改。
3. live 帧的 runId 是请求 runId，snapshot 的 runId 是 executionRunId；本轮不做统一。

## 9. 实施结果（2026-11-01）

已按本设计落地，并通过端到端验证：

- 后端：`tools/*`、`AguiEventProjection`、`ThreadViewAssembler`、`IncrementalEventProjection`、`Payloads`、`ChatController` 新增 `/api/threads/{id}/view` 与 `/view/events`。
- 前端：`types/chat.ts`、`ToolCardView`、`ServerViewPanel`、`App` 三栏布局、`vite.config.ts` 增加 `/api` 代理。
- 实测：真实模型成功调用 `lookup_order` / `write_note` / `search_docs`；B 路线快照与增量均产出订单卡/文件卡/搜索卡；重启后 9 项视图与 diffStats 保持不变；live 帧序为 `TOOL_CALL_START → ARGS → CUSTOM → END → RESULT → TEXT → RUN_FINISHED`，结果帧只发一次。
- 测试：`mvn test` 12 个测试通过；`npm run build` 通过。

实现中对设计做了一处收敛：B 路线增量不再逐片下发 `card-args`，而是在 `tool/requested` 时一次性下发完整参数。依据是 6.1.9“前端卡片等待参数完整后再展示，不展示生成中的半成品参数”；live A 路线仍保留 `TOOL_CALL_ARGS` 分片，由 CopilotKit 自行拼接。

新增的验证结论：

1. AG-UI 标准帧没有工具进度帧，`tool/chunk` 只能落 `CUSTOM`；CopilotKit v2 `useRenderTool` 不暴露 progress。
2. 工具返回 `String` 会被默认 converter 二次 JSON 编码；演示工具统一返回 `Mono<ToolResultBlock>`。
3. `action/end` 的 result 可能仍是 `RUNNING`，真正终态在随后的 `tool/result` 消息里；增量投影需要等终态再发 `card-result`。
4. 模型 tool_use 分片里的 `name` 可能是 `__fragment__`，需要从 `ToolCallsAccumulator.buildAllToolCalls()` 回解真实工具名。
