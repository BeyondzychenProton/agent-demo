# 海卓大脑 · 分页历史 + 增量展示 + CopilotKit 输入 Demo


> **2026-11-02 当前页面已升级**：历史、工具卡片和审批共用v2增量展示读模型，默认加载最近50项，长正文按块展开，前端动态高度窗口化。CopilotKit保留公共输入组件与会话目录接入；旧AG-UI接口继续兼容，但当前页面不再通过connect全量加载正文。实施与验证见[会话加载优化实施记录](docs/learning/会话加载优化实施记录.md)。下方原接口说明及旧版前端联调结果保留作兼容参考。

基于 AgentScope `2.1.0-BETA1`（HarnessAgent + 本地 WorkspaceSessionLogStore）+ Spring Boot WebFlux，
在已验收的后端主干之上，叠加一层自研薄 AG-UI 适配与裁剪后的 CopilotKit 前端，验证技术大纲 **5.4**：

> 提交任务 → committed 事实落 SessionLog → 快照现算视图 → SSE 按 cursor / Last-Event-ID 续增量 → 重启后从本地日志完整恢复；前端经 AG-UI 标准帧（info/threads/connect/run）连接，多会话列表 + 气泡流 + 历史快照恢复 + 逐字流式。

**已接入首轮 HITL**：会议预约和取消等写操作执行前需要人工审批，支持批准、拒绝和等待期间取消。

**不含**：信息补充表单、批量审批、参数修改后批准、执行中取消、steer/inject、A2UI/状态面板/PlanBoard、多实例审批协调、Redis/JDBC/OSS、鉴权、run 级裁剪。

### 首轮人工审批使用方式

发起会议预约或取消后，Agent 提出写操作时，在页面审批卡片中批准或拒绝。页面分别展示人工决定和工具执行结果；提交超时后先查询当前结果，不把超时视为未接收。

- 查询：`GET /api/threads/{id}/view`，联合执行日志和 Inbox 恢复完整交互状态。
- 答复：`POST /api/threads/{id}/interactions/{requestId}/answer`，请求体为 `{"decision":"approve","reason":""}`；拒绝使用 `reject`。
- 取消：`POST /api/threads/{id}/turns/{turnId}/cancel`，仅支持尚未接收答复的等待阶段。
- 通知：`GET /api/threads/{id}/interactions/events`，从双日志水位发现变化，触发页面重新查询，不复用执行日志 cursor。
- 接收协调为单进程会话锁；多设备可连接同一实例，多实例部署需要共享协调。
- 持久记录复用框架日志与 Inbox，不建立独立审批表。执行中崩溃或外部效果不确定时不承诺自动重跑。
当前注册会议 MCP 工具与会议时间解析所需的时钟工具；技能加载和异步等待由框架提供。
后端原有 `/api` 最小闭环接口保持不变，与 `/agui/run` 协议面并存。

### 后端阅读顺序

1. `ag-ui/src/main/java/com/beyondzy/demo/session/ChatSessions.java`：看 `approvalPermissions` 确定审批触发规则，接着看 `answer`、`view` 与 `interactionRevision` 的接入关系。
2. `ag-ui/src/main/java/com/beyondzy/demo/hitl/HitlService.java`：沿 `answer → interactions → project → decorate` 理解决定接收、双日志恢复和卡片组装；`cancel` 是等待期间取消的独立分支。
3. `ag-ui/src/main/java/com/beyondzy/demo/web/ChatController.java`：查看审批与取消接口、冲突响应，以及不复用执行 cursor 的变化通知。
4. `ag-ui/src/main/java/com/beyondzy/demo/contract/ThreadView.java`：查看 `HitlBlock` 字段注释，区分已接收的决定、框架处理状态与后续执行。
5. `ag-ui/src/test/java/com/beyondzy/demo/session/HitlIntegrationTest.java`：使用不联网的脚本模型，驱动真实 Harness、SQLite 和文件工具；恢复点与并发场景在测试中明确构造。

## 1. 环境要求

- JDK 17+（本机用 Microsoft OpenJDK 21：`D:\code environment\jdk\jdk21`）
- Maven 3.9+（本机：`D:\code environment\apache-maven-3.9.14`）
- Node 18+ / npm（前端 dev 用，本机 node v22）
- 模型走 DashScope **OpenAI 兼容端点** `compatible-mode/v1`，因此用 `OpenAIChatModel`（非原生 DashScope SDK）

## 2. 配置（ag-ui/src/main/resources/application.properties）

| 配置 | 说明 |
|---|---|
| `chat.base-url` | OpenAI 兼容接口基地址 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `chat.endpoint-path` | 补全路径 `/chat/completions`，与 baseUrl 拼成 `/v1/chat/completions` |
| `chat.model` | 模型 API ID `deepseek-v4.1-flash-1017`（用 API ID，不用显示名/空格写法） |
| `chat.api-key` | 私有 key，**只存本地本文件，勿提交版本库、勿打印日志**；也可用环境变量覆盖 |
| `chat.workspace` | SessionLog 落盘根目录，默认 `~/.agentscope/haizhuo-demo`，**重启恢复的权威来源** |
| `server.port` | 默认 8087，仅监听 127.0.0.1 |
| `mcp.endpoint` | 本地直连 MCP 地址，默认 `https://cms-test-k8s.shsets.com/stage-api/mcp` |
| `mcp.app-key` / `mcp.secret` | 测试环境 App Key 与 HMAC Secret，环境变量 `MCP_APP_KEY`、`MCP_SECRET` |
| `mcp.discovery-phone` | 仅启动时 `tools/list` 使用的满天星有效员工手机号；业务调用仍用当前登录用户 |

所有项都可用同名环境变量覆盖（Spring 占位符）；`chat.api-key` 留空时启动即报中文错误，不静默降级。


### 2.1 本地直连满天星 MCP

进程按 `mcp.endpoint` 直接调用测试环境 MCP。会议技能通过 `POST /api/skills/archive` 上传 ZIP 并保存在数据库中，上传内容应与实际 MCP 工具契约一致。

- 启动时用 `mcp.discovery-phone` 做一次 `initialize` 和 `tools/list`，把工具注册进会话。点号会变成下划线，例如 `identity.resolve` 注册为 `identity_resolve`。
- 每次 `tools/call` 重新用**当前登录用户**的手机号签名。模型参数里的手机号只当作参会人等业务参数，不能换成发起人。
- `room_reserve` 和 `meeting_cancel` 走人工批准；查询工具直接执行。
- 启动后看 `GET /agui/run/info` 的 `mcp.registeredTools`。发现失败只记警告，本地演示工具和已有会话仍可使用。
- 写操作不自动重试。`IN_PROGRESS` 或结果不确定时停止，不要更换 `requestId` 再提交。

## 3. 后端构建与启动（PowerShell）

```powershell
# 指定 JDK21（本机 JAVA_HOME 默认未设）
$env:JAVA_HOME = 'D:\code environment\jdk\jdk21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

cd D:\qwenWork\agentscope-learn-demo\ag-ui
mvn -DskipTests package
java -jar target\ag-ui-1.0-SNAPSHOT.jar
```

开发期也可 `mvn spring-boot:run`。key 已在配置文件内，启动无需再 export。后端监听 `127.0.0.1:8087`。

## 3.1 前端启动（CopilotKit 最小聊天窗）

前端在 `frontend/`，dev 服务器 `5173`，Vite 把 `/agui` 代理到 `127.0.0.1:8087`：

```powershell
cd D:\qwenWork\agentscope-learn-demo\frontend
npm ci            # 首次安装依赖
npm run dev       # 开发模式，浏览器打开 http://localhost:5173
npm run build    # tsc 类型检查 + 生产打包到 frontend/dist
```

> 注意：本机 Vite 默认只绑定 IPv6 `::1`，请用 `http://localhost:5173` 访问，不要用 `127.0.0.1:5173`（会被拒绝）。

## 4. 接口表（仅后端 API）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/sessions` | 已落盘会话列表 + 当前模型名 |
| GET | `/api/sessions/{id}` | 现算快照：messages + 聚合 items + turns + pending + `cursor` |
| POST | `/api/sessions/{id}/turns` | 提交一次任务，body=`{"request_id":"...","message":"..."}`，返回 turnId/runId/status |
| GET | `/api/sessions/{id}/stream` | SSE 增量：`?after=cursor` 或 `Last-Event-ID` 头取较大水位，300ms 轮询 committed，15s 心跳 |

## 4.1 AG-UI / CopilotKit 协议面（`/agui/run`，自研薄适配）

固定 agentId=`chat`；`SessionLog` 保存会话事实，工作区内的 `thread-catalog.sqlite3` 保存可重建的列表摘要和已完成会话展示快照。快照仅在日志序号匹配时复用。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/agui/run/info` | 声明 agent `chat`、`threads=true`、`mode=sse` 等能力 |
| GET | `/agui/run/threads?limit=20&cursor=...` | 按摘要更新时间倒序分页，返回 `nextCursor`；首次升级时从既有日志补建摘要 |
| POST | `/agui/run/threads` | 新建线程，只发合法 id，惰性落盘 |
| PATCH | `/agui/run/threads/{id}` | 重命名（不落表，仅回显） |
| POST | `/agui/run/threads/{id}/archive` | 归档（不删日志，仅回显成功） |
| DELETE | `/agui/run/threads/{id}` | 删除（不删日志，仅回显成功） |
| GET | `/agui/run/threads/{id}/messages` | 历史消息列表（CopilotKit 主要靠 connect 快照恢复） |
| POST | `/agui/run/agent/chat/connect`（SSE） | 回放历史；有活动 Run 时补发已提交的文本增量并继续等待终态，无活动 Run 时以 `RUN_FINISHED` 结束握手 |
| POST | `/agui/run/agent/chat/run`（SSE） | 提交消息，流出 `RUN_STARTED → TEXT_MESSAGE_START → TEXT_MESSAGE_CONTENT(delta 逐字) → TEXT_MESSAGE_END → RUN_FINISHED` |

run 流的事件翻译：以提交前 head.seq 为水位，300ms 轮询 committed 日志，按框架真实 runId 过滤；`model/chunk` 的 `content[].type=text` 翻成 `TEXT_MESSAGE_CONTENT` delta，`run/end` 收口；未收到 chunk 时用落定 `message/assistant` 全文兜底。

### 4.2 工具卡片视图与增量（6.1.8 契约）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/threads/{id}/view` | M4 快照契约：`view`（MessageItem + ToolCardViewModel）、`pendingInteractions`、`asOfSeq`、`cursor`、`activeRun` |
| GET | `/api/threads/{id}/view/events?after=cursor` | committed 批次 → `IncrementalEvent`（message-delta / card-args / card-status / card-result / run-finished），SSE + Last-Event-ID + 15s 心跳 |

工具事件映射：`tool/requested` → `TOOL_CALL_START` + 完整参数；`model/chunk` 工具分片 → `TOOL_CALL_ARGS` delta；`tool/chunk` → `CUSTOM(tool-progress)`；`action/end` / `tool/result` → `TOOL_CALL_END` + `TOOL_CALL_RESULT`；`run/end` 前闭合所有 open text/tool。

会议工具在启动时从 MCP 动态发现；`get_current_time` 用于解析会议相对日期。旧工具卡片的展示兼容逻辑仍用于历史记录。

> 从「模型吐 tool_use」到「前端卡片」的逐步讲解见 `docs/learning/工具调用事件全链路说明.md`。
> 快照/增量契约逐字段说明见 `docs/learning/ThreadView契约说明.md`。
> `snapshot` 包（ChatHistory / ChatItems / ThreadViewAssembler / IncrementalEventProjection）的职责与协作见 `docs/learning/snapshot包说明.md`。
> 前端卡片对接（接口、字段、mock）见 `docs/integration/前端卡片对接指南.md`。
> 新会话交接见 `docs/交接说明.md`；文档总目录见 `docs/README.md`。

## 5. 五步闭环验证（已实测通过）

| 步 | 操作 | 实测结果 |
|---|---|---|
| ① | `GET /api/sessions` | 200，`model=qwen3.8-max`，`sessions=[]` |
| ② | `POST /sessions/demo1/turns` 发消息 | 返回 commandId/turnId/runId，`status=starting`，committed 落日志 |
| ③ | SSE：带 `Last-Event-ID: demo1:43` 连接 | 只收 `:connected`，**不重放 43 条历史**；改 `after=demo1:43` 发第二条，只收到 seq 44–97 共 54 帧新事件 |
| ④ | `GET /sessions/demo1` 快照 | cursor 推进到 `demo1:97`，4 条消息（2 USER + 2 ASSISTANT）、2 个 turn 均 completed，多轮完整累积 |
| ⑤ | Ctrl+C 停服务后重启 | 列表仍返回 `demo1`，快照与重启前逐字一致（本地 SessionLog 恢复，不依赖内存/实时流） |

手动复现命令：

```powershell
# ① 列表
curl.exe -s http://127.0.0.1:8087/api/sessions

# ② 提交
curl.exe -s -X POST http://127.0.0.1:8087/api/sessions/demo1/turns `
  -H "Content-Type: application/json" -d '{\"request_id\":\"r1\",\"message\":\"用一句话介绍你自己\"}'

# ③ SSE 水位续传（带 Last-Event-ID 不重放历史；-N 不缓冲）
curl.exe -N -H "Last-Event-ID: demo1:0" http://127.0.0.1:8087/api/sessions/demo1/stream

# ④ 快照
curl.exe -s http://127.0.0.1:8087/api/sessions/demo1
```

## 6. 代码与大纲对应

后端按大纲模块分层：

```text
com.beyondzy.demo
├─ ChatApplication.java              // 启动入口
├─ config/                           // 模型 + ChatSessions 装配
├─ web/                              // HTTP/SSE 接入
│  ├─ ChatController.java            // /api
│  └─ agui/                          // /agui/run：Controller + Service
├─ session/                          // M-Runtime / M1 / M2
│  ├─ ChatSessions.java
│  └─ ThreadCatalog.java
├─ snapshot/                         // M4：快照、卡片聚合、增量投影
│  ├─ ChatHistory.java
│  ├─ ChatItems.java
│  ├─ ThreadViewAssembler.java
│  └─ IncrementalEventProjection.java
├─ projection/                       // M3：SessionEvent → AG-UI 帧
│  └─ AguiEventProjection.java
├─ contract/                         // 前端契约 DTO
│  └─ ThreadView.java
├─ tools/                            // 时钟工具与历史工具卡片元数据
│  ├─ ToolCatalog.java
│  ├─ ClockTools.java
│  └─ LineDiff.java
└─ support/                          // 跨模块纯工具
   ├─ Payloads.java
   └─ ContentValues.java
```

已实现：`hitl/`（M6 工具审批）。

| 类 | 对应大纲模块 | 职责 |
|---|---|---|
| `ChatApplication` | 启动入口 | 只保留 `@SpringBootApplication` 与 `main` |
| `config/ChatConfiguration` | M-Runtime 装配 | OpenAI 兼容模型 + SessionLogStore 接线 |
| `session/ChatSessions` | M-Runtime / M2 薄封装 | 会话列表、提交、readAfter 增量读取、启动恢复 |
| `session/ThreadCatalog` | M1 ThreadService | 会话列表读索引、分页、展示快照 |
| `snapshot/ChatHistory` | M4 HistorySnapshot | scan 已提交前缀 → transcript → cursor 拼装 |
| `snapshot/ChatItems` | M4 聚合（6.1.6 / 5.4.6） | 半段文本重建、参数分片合并、卡片终态收口 |
| `web/ChatController` | M5 StreamChannel / 接入 | 快照端点、SSE committed 轮询 + Last-Event-ID + 心跳 |
| `web/agui/AguiService` | M5 接入（AG-UI 薄适配） | 线程现算、connect 快照、run 流式；事件翻译委托给 `AguiEventProjection` |
| `web/agui/AguiController` | M5 接入（协议面路由） | `/agui/run` 下 info/threads/connect/run 路由，不动主干 |
| `projection/AguiEventProjection` | M3 EventProjection | SessionEvent → AG-UI 帧：文本、工具参数分片、进度 CUSTOM、run/end 闭合 |
| `snapshot/ThreadViewAssembler` | M4 HistorySnapshot | ChatItems → 6.1.8 `SnapshotResponse`（ToolCardViewModel / MessageItem） |
| `snapshot/IncrementalEventProjection` | M4 / M5 增量 | committed 事件 → `IncrementalEvent`（card-args/status/result、message-delta、run-finished） |
| `contract/ThreadView` | 6.1.7 / 6.1.8 契约 | 快照 / 增量 / 卡片 ViewModel 的前端契约 |
| `support/Payloads` + `support/ContentValues` | 投影辅助 | 统一解码 payload、取文本、解析 JSON、归一卡片状态 |
| `tools/*` | M7 ToolRegistry | 三个演示工具 + `ToolCatalog` 折叠标题/定制卡片标记 + 行级 diff |

前端文件（`frontend/`，三栏工作区：会话列表 / live A 聊天窗 / history B 服务端视图）：

| 文件 | 职责 |
|---|---|
| `src/main.tsx` | 精简 `CopilotKit` 装配：`runtimeUrl=/agui/run`、`useSingleEndpoint=false`、agent=`chat` |
| `src/App.tsx` | CopilotKit输入、统一分页消息、待答入口和按需诊断面板 |
| `src/types/chat.ts` | 6.1.8 TS 契约 + `upsertChatItem` 稳定键归并 |
| `src/components/ToolCardView.tsx` | 统一卡片组件：live/snapshot 共用，订单子视图 + diffStats + HITL 操作与处理状态 |
| `src/components/WindowedTranscript.tsx` | 当前页面的窗口化消息、卡片及长内容按需读取 |
| `src/components/ThreadListPanel.tsx` | `useThreads` 会话列表：新建/切换/刷新（归档/删除为回显 noop） |
| `vite.config.ts` | `/agui`、`/api` 代理到 `127.0.0.1:8087`，dev 端口 5173 |

## 7. 实测备注与已知限制

- **回包口吻**：该兼容网关实际回包为 Kimi/Moonshot 口吻（模型名 `qwen3.8-max` 被网关路由），不影响快照/增量/恢复链路验证；如需严格 qwen 输出，更换 base-url/key/模型名即可，代码不变。
- AG-UI 协议面为**最小薄适配**：线程重命名/归档/删除是回显 noop（不写也不删 SessionLog，数据权威始终是日志）；`GET /threads/{id}/messages` 的 content 为 `{value:...}` 包装对象，前端历史恢复以 connect 的 `MESSAGES_SNAPSHOT`（纯字符串 content）为准。
- 单进程本地 `WorkspaceSessionLogStore`，未接 Redis 热层 / MySQL JDBC / OSS（5.2.2 生产替换点）。
- 会话列表摘要持久化在工作区的 SQLite 读取索引中，按更新时间分页；已完成会话的展示快照按日志序号跨进程复用，活动 Run 始终读取最新事实。首次升级打开某个旧会话时需要扫描一次本地日志并填充索引。
- 性能改造的设计、测量口径和验收项见 `docs/learning/会话加载性能优化方案.md`。
- 审批同会话协调限单进程；旧版演示工具不再注册，历史工具记录仍可展示。
- **AG-UI 标准帧没有工具进度帧**：`tool/chunk` 只能落成 `CUSTOM(name=tool-progress)`；CopilotKit v2 的 `useRenderTool` 只暴露 `inProgress/executing/complete`，不暴露 progress。6.1.9 的“进度进卡片”在 live 路径上需要自定义消费方，标准 hook 做不到。
- **live 与 snapshot 的 runId 口径不同**：live 帧使用 AG-UI 请求 runId，snapshot 使用框架 `executionRunId`；本轮不做统一，卡片按 `toolCallId` 归并没有冲突。
- **现有 `ThreadCatalog` 的 4 张 SQLite 表**（`thread_catalog` / `display_*`）是可重建读索引，但性质上是元数据表 + 页面模型表，与大纲“应用层不建任何业务表”存在取舍。本轮不修改，验证结论记录在 `docs/superpowers/specs/2026-11-01-tool-card-e2e-design.md`。
- **工具返回类型**：返回 `String` 会被框架的默认 converter 整体序列化成 JSON 字符串字面量（出现二次编码）；演示工具统一返回 `Mono<ToolResultBlock>`，并在 `ThreadViewAssembler.parseJson` 做了一层防御性解包。

## 8. 旧版前端联调记录（兼容参考）

| 项 | 实测结果 |
|---|---|
| `GET /agui/run/info` | 返回 agents=`chat`、`mode=sse`、`threads=true` |
| `GET /agui/run/threads` | 现算出 `demo1`，线程名=首条用户消息「用一句话介绍你自己」 |
| `connect` 握手 | 已完成会话以 `RUN_STARTED → MESSAGES_SNAPSHOT → RUN_FINISHED` 恢复；活动 Run 在快照后续接增量，直到原 Run 完成 |
| `run` 流式 | 首帧 `RUN_STARTED`，随后 `TEXT_MESSAGE_START → TEXT_MESSAGE_CONTENT(delta) → TEXT_MESSAGE_END → RUN_FINISHED`，真实模型逐字输出并上屏，控制台无报错 |
| `run` 工具帧 | `RUN_STARTED → TOOL_CALL_START → TOOL_CALL_ARGS×N → CUSTOM(tool-progress)×2 → TOOL_CALL_END → TOOL_CALL_RESULT → TEXT_MESSAGE_* → RUN_FINISHED`；`RUN_FINISHED` 为最后一帧，结果帧只发一次 |
| B 路线快照 | `GET /api/threads/e2e1/view` 返回订单卡 / 文件卡 / 搜索卡；`lookup_order` 结果已解析为对象，`write_note` 带 `diffStats {added:18,deleted:0}` |
| B 路线增量 | `GET /api/threads/e2e1/view/events?after=e2e1:357` 收到 `message-delta → card-status(generating_arguments) → card-args → requested → running → progress×2 → card-result(completed) → message-delta×N → run-finished` |
| 重启恢复 | 停止后端并重启后，`e2e1` 快照仍为 9 项，3 张工具卡状态与 diffStats 不变 |
| 测试/构建 | `mvn test` 12 个测试通过；`npm run build`（tsc --noEmit && vite build）EXIT=0 |
