# MCP 管理数据库与接口设计

日期：2026-11-24。业务规则已通过本轮讨论确认；下述表结构、接口和步骤为实现依据，实施与验证结果见末尾。

MCP 继续通过代码配置接入。数据库保存服务展示信息、服务与工具开关、工具发现快照、连接检测结果和管理操作记录。实际业务调用在发送前读取数据库中的最新开关；管理页面与调用链使用同一份状态。

## 已确认范围

- 实现真实服务列表、服务详情、工具清单与只读参数详情、服务启停、逐工具开放、重新验证连接、管理变更记录。
- 新增 MCP 的接入流程不实现，入口禁用并说明暂由代码配置；不提供连接地址或凭证编辑、服务删除、工具手工新增和删除。
- 移除 MCP 页面、列表、工具清单和确认弹窗中的 Skill、Agent、员工关联、链接与统计；不保留虚构数字或占位统计卡片。
- 本次不读取、写入或维护 MCP 与 Skill 的依赖关系，不做 Skill 整体可用性判断、必需依赖中断、可选依赖降级及自动恢复判断。
- Skill 配置状态保持不变。执行到停用工具时明确返回不可用原因。现有 Skills 模块及其历史依赖数据不在本次修改范围内，不删除其数据，不给本次 MCP 管理增加依赖查询。
- 不实现近 24 小时调用量、成功率等调用统计，移除本地 Mock 固定指标。
- 去掉 MCP 草稿、发布和可编辑版本语义。展示信息使用“保存信息”；服务和工具开关独立确认、提交后生效。
- 地址、传输方式、认证实现来自代码及部署配置，只读展示。运行环境按后续页面批注不在详情中展示。首次上线延续已有工具的开放状态；后续新发现工具默认关闭。
- 连接检测失败保留上次成功目录与人工开关，显示失败原因、时间及历史快照提示。连接健康不自动修改人工开关。
- 发现后新增工具和参数变化需要重启后用于聊天；管理目录更新不表示运行工具已更新。

## 实施前代码事实与设计边界

| 位置 | 当前行为 | 本次改动方向 |
| --- | --- | --- |
| `ag-ui/.../config/chat/ChatConfiguration.java:95` | 从配置创建满天星 MCP Bean | 保留接入方式，补不含秘密的服务描述和调用检查依赖 |
| `ag-ui/.../mcp/McpServer.java` | 提供服务键、发现、调用 | 继续作为协议适配边界，增加最小展示元数据接缝 |
| `ag-ui/.../mcp/McpToolDefinition.java` | name、description、inputSchema、write | 复用输入结构和本地读写策略，不虚构输出结构 |
| `ag-ui/.../mcp/McpToolMount.java:27` | 发现并注册，已有接收快照的 private 重载 | 通过 toolsForMount 和 mounted 接缝，登记与挂载同一份快照 |
| `ag-ui/.../session/ChatSessions.java:203,259` | 启动时注册工具，运行中不刷新 | 保留装配方式，登记当前进程实际加载的快照 |
| `ag-ui/.../mcp/McpBoundTool.java:61` | 审批后实际进入调用 | 保留现有结果转换和当前用户身份传递 |
| `ag-ui/.../mcp/mantianxing/MantianxingMcpClient.java:146,150,203` | 先 initialize，再发送 tools/call | 在 tools/call 的 HTTP 发送前做最终检查 |
| `frontend/src/components/CapabilityManagement.tsx:26,29` | Skills、Agent 独立组件；MCP 仍为 Mock | MCP 切换到独立真实组件，保留其他分支 |

表中 `ag-ui/...` 表示 `ag-ui/src/main/java/com/beyondzy/demo/`。上述为实施前的工作树事实。

服务稳定键沿用代码中的 `mantianxing`，不能以原型中的 `mantianxing-oa` 或 Mock 中的 `meeting-service` 替代。工具稳定键使用远端原始名称，例如 `meeting.list_mine`；模型注册名的点号替换、截断或消歧结果只作为运行信息展示。

当前满天星客户端使用应用配置和签名请求头，并非原型示例的 Bearer 认证。页面显示真实认证类型与“凭证已配置”，不返回 app key、secret、签名、发现手机号或认证请求头。输出结构当前未被工具模型采集，详情显示“未提供输出结构”，不从业务调用结果推测契约。

## 状态与生效规则

### 服务和工具开关

管理开关与运行状态分开表达：

```text
管理允许 = 服务 enabled && 工具 enabled
可以进入调用 = 管理允许 && 工具存在于当前可用目录 && 当前进程已加载该工具
```

连接健康是最近一次检测结果，不放入上面的人工开关表达式。检测失败后，调用仍按管理允许状态尝试，真实远端失败按现有机制返回；不把健康快照当永久授权或实时连通性保证。

停用服务不改工具开关。恢复服务后，原来单独关闭的工具仍关闭。服务停用期间允许调整工具开放状态，但提示“服务当前停用，调整后仍不可调用”。成功发现确认工具已消失时，标记工具不在当前目录并阻止调用，保留其开关和历史；同名工具再次发现时沿用原有人工状态。

### 调用检查时机

1. 可在业务调用入口早检，减少明显已停用时的初始化请求。
2. 满天星会先发送 initialize 和 initialized 通知；这段时间内可能发生停用，因此早检不能代替最终检查。
3. 在发送 `tools/call` 的 `http.send` 前，以原始 provider/tool 键直接查询主库。同一条查询读取服务开关和工具开关、存在状态；不得读从库、JVM 缓存或包含旧状态的长事务快照。
4. 拒绝时不发送 tools/call，返回结构化失败，交给现有 `McpBoundTool` 转成工具错误。查询异常同样不放行；普通聊天继续可用。
5. 等待审批的调用获批后仍经过同一最终检查。审批已批准与执行被拦截分别保留，不把批准记录改为拒绝。
6. 已进入执行的请求继续完成，不自动取消会议、不补偿写入、不自动换 requestId 重试。

最终数据库读取是调用准入的判定点：停用提交在此次读取之前完成，该调用必须被拦截。读取之后到 HTTP 提交之间存在很短的本地间隙，该请求已获准进入执行；不为消除这个间隙持有数据库锁跨越远端请求，也不承诺撤回已获准或已发送的请求。

当前注册策略下，关闭的工具可以仍出现在模型的工具目录中，但执行一定被拦截；本次不改造每轮工具目录。服务端保留原有当前登录用户身份、写操作审批与源系统鉴权，管理开放不替代它们。

### 首次登记与后续发现

- 首次上线时，以启动发现并用于挂载的同一份快照建立基线；该快照中首次登记的工具默认开放，服务默认启用，延续原有行为。
- 本次兼容基线的服务键显式限定为当前既有的 `mantianxing`；后续代码新接入的服务不适用自动开放基线，避免把每家服务的“首次发现”都误作本次系统上线。
- 基线初始化标记持久化，并在事务中锁定服务记录。重启或多进程启动不能再次初始化、覆盖人工开关。
- 后续发现的新工具默认关闭，包括后续由代码增加的新服务中的工具；新服务默认启用只表示服务总开关允许，工具仍需明确开放。
- 只对新记录设置默认值；冲突更新仅更新发现字段，禁止覆盖 enabled、人工元数据和 control_version。
- 首次发现失败不标记基线完成。若管理员先通过验证建立目录，新增工具按关闭处理；之后启动不能把已有记录自动改为开放。
- 服务停用也不跳过正常的启动挂载，否则恢复启用会额外依赖重启。调用始终受最终检查约束。
- 代码移除服务后，管理页显示“配置已移除”，不允许启用或验证，不删除历史。配置重新出现时，沿用该稳定键原有开关。配置移除须完成相关进程重启，不能由单个实例推断全环境配置已移除。

### 发现快照与运行快照

成功的完整发现结果用于更新管理目录。空数组可以是有效结果；超时、分页未取完、重复工具键、响应损坏不得作为完整成功结果覆盖旧目录。当前分页最多读取 20 次的保护逻辑需在仍有 nextCursor 时显式报错，不能静默接受截断结果。

启动时用同一份发现结果登记并挂载，避免两次 tools/list 读到不同目录。每个进程内保存实际加载的工具键、注册名和规范化定义摘要，用于与最新发现摘要比较。摘要覆盖名称、说明、输入结构和本地读写策略，JSON 对象键规范化，沿用挂载时缺少 schema 的回退规则，避免误报差异。

| 发现结果与本进程加载情况 | 页面提示 |
| --- | --- |
| 已发现且当前进程已加载相同定义 | 已加载 |
| 新发现但当前进程未加载 | 待重启加载 |
| 已加载但输入结构或定义发生变化 | 待重启更新 |
| 完整成功发现确认已移除 | 已从远端移除，不可调用 |
| 本次检测失败，保留此前成功结果 | 历史快照，显示最近失败及上次成功时间 |

参数变化在重启前不会热替换模型看到的结构。远端已改变契约时，原结构调用可能失败，页面不能保证旧参数仍被远端接受。成功重启加载后使用新结构，工具开放状态保持原值。

不新增集群运行登记表。运行快照明确限定“当前响应实例”，同时返回实例标识和启动时间；多副本部署完成全部实例重启后才能认为全环境已更新。最终调用检查在每个实例执行前查询同一主库，因此管理开关不依赖实例重启。

## 数据库结构

新增三张表，迁移为 `V14__mcp_management.sql`；手工执行，本次 MCP 迁移不由应用启动执行。日期时间使用 `DATETIME(3)`，Java 实体沿用 `BaseAuditBean`；接口时间返回带时区的 ISO 8601，页面按 Asia/Shanghai 展示。

连接地址和传输方式在接口中从本进程配置描述读取，不建立数据库连接配置覆盖层。业务键使用区分大小写的比较规则，避免不同大小写的工具被唯一索引合并。主键、操作者 ID 在 JSON 中按字符串返回。

### ai_mcp_service

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| id | BIGINT UNSIGNED PK AUTO_INCREMENT | 管理记录主键 |
| provider_key | VARCHAR(128) NOT NULL UNIQUE | 稳定代码服务键 |
| display_name | VARCHAR(128) NOT NULL | 管理展示名，可修改 |
| description | VARCHAR(1000) NULL | 管理说明，可修改 |
| owner_name | VARCHAR(128) NULL | 负责人展示文本，不承担授权含义 |
| enabled | TINYINT UNSIGNED NOT NULL | 1 启用，0 停用 |
| control_version | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 人工修改乐观锁 |
| catalog_initialized | TINYINT UNSIGNED NOT NULL DEFAULT 0 | 基线建立标记 |
| catalog_revision | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 成功目录版本号 |
| check_revision | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 检测序号，防止旧结果覆盖新结果 |
| last_check_state | VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' | UNKNOWN、CHECKING、HEALTHY、FAILED |
| last_check_started_at | DATETIME(3) NULL | 最近检测开始时间 |
| last_checked_at | DATETIME(3) NULL | 最近已应用检测完成时间 |
| last_success_at | DATETIME(3) NULL | 最近成功发现时间 |
| last_latency_ms | INT UNSIGNED NULL | 最近一次完整检测耗时，失败也可记录 |
| last_error_code | VARCHAR(64) NULL | 归类错误码 |
| last_error_message | VARCHAR(1000) NULL | 经过脱敏的错误摘要 |
| updated_by | BIGINT UNSIGNED NULL | 最近人工操作的 app_user.id |
| gmt_create、gmt_modified | DATETIME(3) NOT NULL | 建立及更新时间 |

检测更新不修改 control_version、updated_by。页面“信息更新时间”取最近人工修改记录，检测时间独立显示；不得把一次检测冒充负责人编辑。

### ai_mcp_tool

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| id | BIGINT UNSIGNED PK AUTO_INCREMENT | 工具管理主键 |
| service_id | BIGINT UNSIGNED NOT NULL | 所属服务 |
| tool_key | VARCHAR(255) NOT NULL | 远端原始工具名 |
| description | TEXT NULL | 最近完整成功发现的说明 |
| input_schema_json | JSON NULL | 最近完整成功发现的输入结构 |
| write_flag | TINYINT UNSIGNED NOT NULL | 本地策略判定的写操作标记，只读展示 |
| definition_hash | CHAR(64) NOT NULL | 规范化定义 SHA-256 摘要 |
| enabled | TINYINT UNSIGNED NOT NULL | 工具独立开放状态 |
| present | TINYINT UNSIGNED NOT NULL | 最近完整成功目录中是否存在 |
| control_version | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 人工开关乐观锁 |
| first_discovered_at | DATETIME(3) NOT NULL | 初次发现时间 |
| last_seen_at | DATETIME(3) NOT NULL | 最近成功发现包含该工具的时间 |
| updated_by | BIGINT UNSIGNED NULL | 最近人工开关操作者 |
| gmt_create、gmt_modified | DATETIME(3) NOT NULL | 建立及更新时间 |

唯一索引 `(service_id, tool_key)`，查询索引 `(service_id, present, enabled)`。遵循现有表的关联检查方式，不新增物理外键，不建立任何 Skill 关联表。超过字段长度或同一结果内原始工具键重复时，整次发现失败，不截断或拼接成另一个工具键。

不保存工具参数实际值、业务响应或输出 schema 猜测值；管理详情只渲染声明的 inputSchema，不执行 schema 内的示例、引用或脚本。

### ai_mcp_operation

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| id | BIGINT UNSIGNED PK AUTO_INCREMENT | 操作记录主键 |
| service_id | BIGINT UNSIGNED NOT NULL | 所属服务 |
| tool_id | BIGINT UNSIGNED NULL | 工具操作时填写 |
| action | VARCHAR(40) NOT NULL | METADATA_UPDATED、SERVICE_STATUS_CHANGED、TOOL_STATUS_CHANGED、CONNECTION_VERIFIED、CATALOG_SYNCED |
| actor_id | BIGINT UNSIGNED NULL | 管理员 app_user.id；系统发现时为空 |
| actor_name | VARCHAR(128) NULL | 操作时的展示名快照；不存电话号码 |
| origin | VARCHAR(16) NOT NULL | ADMIN 或 SYSTEM |
| result | VARCHAR(16) NOT NULL | SUCCESS、FAILED 或 SUPERSEDED |
| detail_json | JSON NULL | 允许字段白名单内的修改前后值、工具增减摘要或脱敏错误 |
| gmt_create | DATETIME(3) NOT NULL | 操作时间 |
| gmt_modified | DATETIME(3) NOT NULL | 沿用 BaseAuditBean 的审计字段，追加后不修改 |

索引 `(service_id, gmt_create, id)`；操作记录只追加。人工修改与记录写入同一事务。此表记录管理操作，不采集每次工具调用或调用统计。

## 管理接口

统一前缀 `/api/admin/mcp/services`。所有接口复用当前登录态，并在后端检查平台管理员权限；前端隐藏按钮不能替代鉴权。JDBC 与远端同步调用放在 boundedElastic，避免阻塞 WebFlux 事件线程。所有列表 size 最大 100，默认 20，排序增加 id 作为稳定次序。

| 方法与路径 | 请求和返回要点 |
| --- | --- |
| GET `/` | q、state=all/enabled/disabled、transport、health、page、size；返回服务分页、total、启停分类计数 |
| GET `/{id}` | 展示信息、只读配置摘要、人工开关、最近检测、上次成功时间、工具计数、controlVersion、当前实例信息 |
| PUT `/{id}/metadata` | displayName、description、ownerName、expectedVersion；返回保存后的信息及新 controlVersion |
| POST `/{id}/status` | enabled、expectedVersion；返回服务开关及新 controlVersion |
| GET `/{id}/tools` | q、state=all/open/closed、page、size；返回工具分页及发现／加载差异 |
| GET `/{id}/tools/{toolId}` | 原始工具名、说明、inputSchema、读写性质、工具开关、存在状态、当前实例加载状态 |
| POST `/{id}/tools/{toolId}/status` | enabled、expectedVersion；校验工具属于该服务，返回新状态和版本 |
| POST `/{id}/verify` | 无业务参数；执行 initialize + 完整 tools/list，保存检测及目录结果，返回结果和待重启摘要 |
| GET `/{id}/operations` | page、size；按时间与 id 倒序分页返回真实管理变更 |

服务列表只读数据库快照和代码描述；浏览列表不为每行发送远端探测。刷新按钮重新请求当前筛选下的列表与计数，不静默清空筛选。只有“重新验证连接”发起远端检测；它可以在服务停用时使用，但不能改变服务或工具开关。

服务过滤计数按搜索、传输方式、健康筛选后的集合计算，暂不应用当前 state 标签，保证切换标签时口径一致。已发现工具数为 present=1 的数量；已开放工具数为 present=1 且工具 enabled=1 的数量，独立于服务总开关。当前实例已加载数量单独表达，不能拿发现数代替。

工具状态响应建议：

```json
{
  "id": "21",
  "providerKey": "mantianxing",
  "toolKey": "meeting.list_mine",
  "enabled": true,
  "serviceEnabled": false,
  "present": true,
  "loadedInCurrentInstance": true,
  "definitionChanged": false,
  "invocationAllowed": false,
  "unavailableCode": "MCP_SERVICE_DISABLED",
  "controlVersion": 3
}
```

`invocationAllowed` 只表示该实例的管理状态和挂载条件允许进入调用，不证明当前员工业务鉴权通过或远端实时健康。元数据请求不接受 enabled、endpoint、transport、credential、版本或工具定义字段；未知管理字段报 400，避免客户端误以为已保存。

### 返回错误

管理接口使用结构化 `{ code, message }`，沿用全局异常处理；缺少登录态 401，非管理员 403，资源不存在 404，参数错误 400，人工版本冲突 409，数据库或检测内部设施不可用 503。

远端鉴权失败等预期检测失败作为一次已完成检测返回 HTTP 200，主体 `success=false` 并含归类错误，持久化失败检测及审计。管理接口本身未获授权或存储失败仍使用对应 HTTP 错误，不混入远端业务失败。

调用拦截沿用 `McpCallResult.failure`，不伪造 OA 响应：

| code | 展示原因 |
| --- | --- |
| MCP_SERVICE_DISABLED | 该 MCP 服务已停用 |
| MCP_TOOL_DISABLED | 该工具已关闭 |
| MCP_TOOL_UNAVAILABLE | 该工具不在当前有效目录中 |
| MCP_TOOL_NOT_LOADED | 当前进程尚未加载该工具，请管理员完成重启 |
| MCP_STATE_UNAVAILABLE | 暂时无法读取 MCP 管理状态，本次未执行 |

返回提示说明本次未执行，不自动触发同一写入的重试或更换 requestId。远端自身的认证、传输及业务错误继续保留现有归类。

## 检测事务与并发

人工保存使用 `UPDATE ... WHERE id=? AND control_version=?`；成功后递增版本并在同一事务追加审计。相同版本和相同状态的重复提交可返回当前值，不重复写管理变更；旧版本提交返回 409 并提示刷新。目录同步仅更新发现字段，不覆盖人工开关。

验证接口采用同步响应，不引入任务队列、后台任务中心或轮询接口。每次检测先以短事务递增 service.check_revision，记录开始时间和 CHECKING，再在事务外执行网络请求，最后按本次序号提交结果。只允许最新序号应用快照；较早完成的结果返回并记录 SUPERSEDED，不能覆盖较新检测。启动发现也使用相同序号规则。

成功提交在一个事务中更新或插入全部工具、标记本次未出现的历史工具 present=0、递增 catalog_revision、更新健康信息并追加审计。失败只更新该次最新检测的健康信息和记录，保留工具快照、last_success_at 及全部人工开关。远端结果在事务外完整校验后才能提交。

检测中途进程退出时，保留的 CHECKING 不是健康成功。页面在超过整次检测总时限后显示“检测中断，可重新验证”；后续验证可取得新序号。实现整次验证的总超时与分页/工具数边界，不能只依赖每个 HTTP 请求 15 秒的超时，否则多页发现会长时间占用请求。

## 页面调整

列表按 Figma V2.4 的服务管理语义实现，展示服务名／稳定标识、传输方式、管理启停、最近连接健康与耗时、已发现／已开放工具数、操作。去掉草稿分类、手填版本、分类筛选、近 24 小时调用指标和全部关联栏。新增 MCP 禁用并注明由代码配置接入；页面只显示真实配置服务，不补入演示服务。

详情展示基本信息、只读连接摘要、最近检测与历史快照提示、工具分页、真实管理变更。保留“已发现工具”和“已开放工具”卡片。工具详情展示原始名、说明、输入结构与必填字段、读写性质、开放状态、当前实例加载提示；没有输出结构时明确说明。

基本信息仅名称、说明和负责人可编辑，使用“保存信息”。启停弹窗只确认目标、状态变化和执行边界，不查询或展示 Skill、Agent、员工。服务停用提示“后续尚未进入执行的工具调用将被拦截；已提交请求继续处理”；工具停用提示同样的单工具范围。

前端对开关请求使用 busy 状态，接口成功后更新服务/工具并刷新相关计数，失败恢复原显示；409 提示刷新，不静默覆盖他人修改。详情信息有未保存修改时保留离开保护。错误、空结果、加载失败、检测中状态均有明确展示。

## 实施顺序

1. 新增手工迁移脚本、MyBatis-Plus 实体／Mapper 和目录状态服务；不在启动自动建表。实现主键、唯一键、CAS 与审计事务。
2. 为配置服务补不含秘密的描述；一次启动发现复用同一快照，建立持久化基线、加载摘要。表缺失或数据库不可用时明确报管理状态异常，普通聊天仍可启动，MCP 不可绕过检查执行。
3. 增加最终调用检查，覆盖初始化期间停用和待审批获批场景。原有手机号身份解析、写审批、工具结果解释、requestId 语义保持不变。
4. 实现九个管理接口、完整发现校验、失败保留与检测并发序号；不修改 Skill 资源目录或依赖判断来接入 MCP 开关。
5. 新增 `McpManagement.tsx` 及 API／类型模块，替换 MCP Mock 分支，复用后台样式与通用对话框；移除本次范围排除的 UI。
6. 完成有针对性的后端集成测试、前端逻辑检查与构建、真实浏览器接口验收。准备可审阅迁移及变更，不覆盖现有 application.properties 和用户未提交文件。

## 验收条件

| 场景 | 必须观察到的结果 |
| --- | --- |
| 真实目录 | 仅展示配置服务；不出现 Mock 服务、固定调用数字、Skill／Agent／员工卡片或关联字段 |
| 持久化 | 修改信息、服务和工具开关后刷新及重启仍保留；记录真实操作者和时间 |
| 总分开关 | 关闭一个工具后停用再启用服务，该工具仍关闭，其他工具按自身状态恢复允许 |
| 待审批调用 | 产生待审批写调用后停用工具，再批准；最终没有发送 tools/call，显示工具已关闭，审批决定仍为批准 |
| 初始化竞态 | 用可控客户端暂停在 initialize 后，提交停用再释放；tools/call 发送次数为零 |
| 已进入执行 | 在最终检查通过且请求已提交后停用；原请求正常完成，不取消 OA 业务、不重复发送 |
| 多实例最新状态 | 实例 A 提交停用后实例 B 的下一次最终检查拒绝，不依赖 B 重启或缓存过期 |
| 存储失败 | 最终检查查询异常时 tools/call 发送次数为零，返回管理状态不可读；普通聊天可继续 |
| 首次基线 | 首次登记延续已有挂载工具开放；重启、多实例初始化和同步不重置人工关闭 |
| 新工具 | 验证发现新工具后默认关闭，提示待重启；重启只更新挂载，不擅自打开 |
| 参数变化 | 目录显示新结构并提示待重启，运行仍为旧快照；重启后使用新结构，原开关保留 |
| 检测失败 | 超时、远端鉴权失败、分页不完整或坏结构不覆盖旧目录、不改开关，显示具体失败和历史快照 |
| 远端删除／重现 | 完整成功发现可标记消失并拦截；同稳定键重现不重置其人工开关 |
| 并发 | 旧 controlVersion 修改返回 409；较早检测结果不能覆盖较新结果；发现不覆盖人工开关 |
| 权限及字段 | 非管理员无法读写管理接口；响应、审计和日志不包含凭证、签名及发现身份 |
| 范围隔离 | Skill 状态、版本、绑定与依赖数据不变；MCP 管理与调用 gate 不查询 Skill／员工关联；不产生依赖降级行为 |

测试重点复用 `McpToolMountTest`、`MantianxingMcpClientTest` 并增加真实持久化与管理 API 测试，使用可控服务验证实际发送次数。MySQL 测试覆盖唯一键、事务和并发，不用只模拟 Mapper 返回值证明持久化有效。

前端执行 `npm test`、`npm run build`，并增加独立 MCP 浏览器场景。现有 `admin-capabilities.spec.ts` 包含旧 Mock 的新增 MCP／Skills 流程，不作为本次真实 MCP 验收依据。真实 OA 验收先使用发现和只读工具；写调用的阻断测试使用可控服务，不为管理功能验收创建真实会议。

## 改造规模

主要改动集中在三张管理表、九个接口、一个 MCP 独立页面、启动快照登记和发送前检查，属于跨前后端的中等改造。最需要验证的是首次基线不覆盖人工状态、发现与运行目录区分、初始化期间停用，以及多个实例的最新状态读取。

本次不建设运行时工具热注册、配置／凭证管理中心、MCP 与 Skill 绑定体系、员工授权体系或调用统计。这些能力若后续要求加入，需要单独设计，不能通过给当前 Mock 接口补几个字段完成。

## 实施与验证记录

- 已实现 V14 三张表脚本、MyBatis-Plus 持久化、九个管理员接口、配置服务描述、启动快照登记、发送前主库检查、目录完整性校验、检测总超时和并发序号。
- 满天星生产 Bean 显式注入最终调用检查；检查位于 initialize 完成后的 tools/call HTTP 发送前。管理状态读失败拒绝执行，不改写审批决定。
- MCP 页面已替换 Mock，保留原 Skills 和 Agent 组件；新增接入入口禁用，移除草稿、版本、固定调用指标及全部 Skill／Agent／员工关联展示。
- 页面覆盖信息保存、总分开关、参数详情、健康与历史快照、分页及真实管理记录。审计用可读的修改说明展示，不直接暴露内部检测序号。修复了同页启停后信息保存误报版本冲突的问题，同时保留未保存编辑对外部修改的冲突检测。
- 后端最终全量测试 152 项：123 通过、29 跳过、0 失败、0 错误，其中真实 MySQL 测试已在独立空库执行。覆盖初始化期间停用、数据库不可读、并发 CAS、审计回滚、检测结果乱序、新工具默认关闭、重启不覆盖人工开关、大小写工具键及 45 个工具分页。
- 前端 51 项测试通过，TypeScript 与生产构建通过；构建保留既有大包提示。
- 使用隔离 MySQL、独立后端 18089、前端 15175 和受控 MCP 服务进行完整登录后的接口及 Chrome 页面验收，未使用浏览器接口 Mock。验证工具详情、信息保存、服务和工具开关、变更记录、新工具待重启、连接失败保留目录、未知写字段拒绝、未登录拒绝。
- 隔离后端重启后确认服务仍停用、已关闭工具仍关闭、负责人保留，新工具已加载但仍关闭；健康检测更新不改变这些开关。未调用真实 OA 的创建／取消会议。
- 当前业务库未执行 V14，未覆盖现有 application.properties，未提交 Git 或部署其他环境。测试进程和本次创建的隔离库在验收后清理；日志保存在 ag-ui/target/mcp-*.log。

### 使用现有业务环境

1. 核对目标库和当前后端配置，对目标库手工执行 `ag-ui/src/main/resources/db/mysql/V14__mcp_management.sql`。只新增 MCP 表，不执行 Skills 或员工迁移。
2. 保留现有 mcp.endpoint、mcp.app-key、mcp.secret、mcp.discovery-phone 配置；`mcp.environment` 保留为后端兼容字段，当前详情页不展示运行环境。
3. 重启后端，首次成功启动发现将既有满天星工具建立兼容基线。前端重新加载后，在 MCP 管理中核对真实目录、工具数和检测结果。
4. 后续发现新工具默认关闭；明确开放后仍需完成后端重启才能新增挂载。多实例部署须完成所有实例重启。

### 页面批注修正 2026-11-25

- 按用户批注移除服务详情中的“运行环境”及其值，其他服务信息保留。
- 修正后 TypeScript 检查通过。
