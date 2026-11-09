# AgentScope 经治理底座使用工具

日期：2026-11-09。依据本仓库当前代码与 AgentScope `2.1.0-BETA1` 的公开 API，以及治理工程 `docs/integration/agent-http-contract.md`。本工程不依赖治理工程的 Java 模块，不访问其数据库，也不在 Agent 进程里保存 MCP Secret 或直连业务 MCP。

## 治理客户端放在哪里

`com.beyondzy.demo.governance.GovernanceClient` 是唯一的 HTTP 出口，实现 `GovernanceGateway`：

- `GET /api/agent/snapshot/current`
- `POST /api/agent/invoke`

地址和 Agent token 来自服务端配置 `governance.base-url`、`governance.agent-token`（环境变量 `GOVERNANCE_BASE_URL`、`GOVERNANCE_AGENT_TOKEN`）。两者缺一则不创建客户端，聊天仍只使用本地工具。

请求只带 `X-Agent-Token`。`tenantId` / `appId` / `subject` 由治理服务按令牌映射，不从模型参数、浏览器或请求体提交。

## Agent 工具注册和执行的接入点

现有入口仍是 `ChatSessions` 里的一个 `HarnessAgent`。AgentScope 2.1.0-BETA1 的 `HarnessAgent.build()` 会 `Toolkit.copy()`，复制出来的注册表才是运行时使用的那一份。因此启动装配顺序固定为：

1. 若配置了治理客户端，先拉一份当前 Snapshot。
2. 构建工具描述与固定执行器，在交给 `HarnessAgent.builder().toolkit(...)` 之前注册进这份 Toolkit。
3. 构建 `HarnessAgent`；复制后的注册表带上同一次启动装配的描述、Schema、URI 与 `snapshotId`。

本轮不支持运行中在线刷新，也不提供 live Toolkit 切换入口。`GovernanceToolMount.install()` 成功后拒绝再次修改同一份装配。

工具定义使用 `ToolBase`：`getName()`、`getDescription()`、`getParameters()` 对应模型看到的函数；`callAsync(ToolCallParam)` 是执行点。`Toolkit.registerAgentTool` 负责注册，`Toolkit.callTool` 负责按名称分发。读工具在 `checkPermissions` 里返回 `ALLOW`，这样现有非平凡权限表不会把 `identity.resolve` 拦成人工审批。风险标签不是 `read` 的工具继续走原权限表，默认会 ASK，本轮不给它们加直连路径。

## Snapshot 的会话生命周期

治理的 “当前 Snapshot” 每次 `GET /current` 都会新建一条。本工程只在进程启动准备工具时调用它一次。

同一次启动装配里的工具描述和执行器共用同一个 `snapshotId`。执行器在创建时捕获 `snapshotId`、`uri`、`bindingId`、`cardId`、`cardVersion`。模型调用只生成业务 `arguments`。

需要更新工具时：等当前任务结束后重启 `ag-ui` 进程。重启会新建 `ChatSessions`，再拉一份当前 Snapshot 并重新装配。会话历史在 MySQL SessionLog 里，重启不删除、不改写历史消息与用户数据。治理服务暂时不可达时，启动只记警告，本地工具和已有会话继续可用；不增加直连 MCP 兜底。本轮没有后台自动刷新、自动重绑定或自动重试。

撤权验收必须保持原进程和旧 Snapshot：不要先重启。保持原会话再次调用，仍走启动时装上的执行器，治理返回 `AUTHZ_DENIED`。

用户操作：

- 采用新 Snapshot：当前任务结束后重启 `ag-ui` 进程。系统提示词与工具都在启动构建时确定，一并随重启更新。
- 观察撤权：不要重启，在同一会话里再问一次。

## 名称映射

AgentScope Toolkit 本身不限制工具名字符。本工程模型走 OpenAI 兼容接口，函数名使用 `[A-Za-z0-9_-]{1,64}`。`identity.resolve` 注册为 `identity_resolve`。

规则：

- 点号和其他非安全字符替换为 `_`。
- 名称唯一、长度合法，且不与本地工具 `lookup_order`、`search_docs`、`write_note` 冲突时，使用替换后的名字。
- 否则在截断后的名字后附加 `_` 和该工具 URI 的 SHA-256 前 8 位十六进制。
- URI 存在执行器字段里。调用时不解析注册名来猜测 URI。

缺少 `uri`、`name` 或 `inputSchema` 的条目不注册。`inputSchema` 深拷贝后原样作为 `ToolBase` 的参数 Schema，不删 `minimum`、`maxLength`、`required`、`additionalProperties`。

## 结果如何交回 Agent

`GovernanceResults` 按契约区分，不把失败改写成成功文本，也不自动重试：

| 类别 | 条件 | 交给模型 |
|---|---|---|
| 正常业务结果 | `outcome=completed` 且不是工具错误 | `ToolResultState.SUCCESS`，带上 `result`。`RESOLVED`、`CANDIDATES`、`NOT_FOUND` 都留在结果里 |
| 工具执行错误 | `completed` 且 `code=TOOL_EXECUTION_ERROR` 或 `result.isError=true` | `ERROR`，保留工具返回内容 |
| 治理拒绝 | `rejected`，如 `AUTHZ_DENIED`、`SNAPSHOT_BINDING_MISMATCH`、`CONTRACT_REBIND_REQUIRED` | `ERROR`，说明调用未成功。`rebindRequired=true` 时说明需要重新装配 |
| 远端失败 | `failed`，含 `agentMayRetry=true` | `ERROR`，文案写明本轮未自动重试 |
| 结果不确定 | `uncertain` / `EXECUTION_UNCERTAIN` | `ERROR`，禁止当作已成功，也不原样重试 |
| 传输失败或过滤器 401 | 连不上，或响应不是 InvokeResponse | `ERROR`，`code=TRANSPORT_FAILURE` 或 `UNAUTHENTICATED` |

模型若在 arguments 里放入与外层同名的 `snapshotId`、`uri`、`phone`、`tenantId`、`token` 等字段，它们仍作为业务 JSON 原样保留在 `arguments` 内；外层 `uri` / `snapshotId` 始终取自执行器注册时捕获的值，`X-Agent-Token` 始终取自服务端配置。客户端不做字段名黑名单剥离，也不在本地再做一套 Schema 校验；权威校验由治理底座按绑定 Schema 完成。

## 和原有工具的关系

`lookup_order`、`search_docs`、`write_note` 继续由原来的类注册，权限规则不变。治理工具另外注册，不替换、不删除这些本地工具。治理工具没有直连 MCP 的兜底；调用失败就把治理错误交回模型。

未配置治理地址时，系统提示词与原来一致。配置了地址后，提示词追加：有治理工具才用来查人员；多名候选人先确认；没找到如实说明；拒绝或失败时说明没有成功；闲聊不调用工具。

## 自动化与联调状态

| 项 | 状态 |
|---|---|
| `GovernanceClientTest` | HTTP 桩：令牌头、arguments 原样透传与绑定隔离、结果分类、401 过滤器形状、传输失败、不重试 |
| `GovernanceToolAssemblyTest` | 名称映射、Schema 深拷贝、执行器固定绑定且业务参数保留、错误回传、新实例重启装配/旧实例不变、装配后拒绝再替换、撤权拒绝、Harness 选工具与重启保历史 |
| 契约依据 | 治理工程 `docs/integration/agent-http-contract.md`（2026-11-08） |
| 真实 MCP 对话 | 2026-11-09 已通过：专属卡 `cms-agentscope-bridge-ro` + 现有 `/api/sessions` 入口；成功 `RESOLVED`，同会话撤权后 `AUTHZ_DENIED`。脱敏细节见 README §9 |

## 待治理工程对齐（本工程不绕过）

当前契约已足够完成本轮接入。若后续变更，请治理侧明确：

1. Snapshot `tools[]` 字段增删或改名。
2. Invoke 响应 `outcome` / `code` 枚举扩展及 Agent 动作约定。
3. 过滤器 401 是否改为完整 `InvokeResponse` 形状（当前按非统一形状处理）。
