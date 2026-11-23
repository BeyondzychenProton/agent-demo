# Skill 包导入：数据库缺口与建议

核对日期：2026-11-23。范围为 Figma Skills 页的检查与策略、资源与规则、草稿结果，以及与之直接相关的原包查看和发布。不是全管理端数据库评审。

设计来源：https://www.figma.com/design/vngp7ab8i9Jiwj537Rajqz/?node-id=7-1502

## 结论与证据边界

现有结构支持简单导入后的正文、文本资料、启停、可见范围、Agent 绑定；不足以完整支持设计中的导入治理。本文核对仓库 V6/V11 SQL、SkillDocuments、SkillAdminServiceImpl 和 Figma 07/08/09 画板。未连接实际 MySQL，不能据此断言线上或本地数据库已应用哪些迁移。建议 DDL 未执行，也未在 MySQL 上验证。

V6 框架表采用 created_at/updated_at，V11 业务关系表采用 gmt_create/gmt_modified；V7 与 V11 的排序规则也不同。新增 Skill 业务表建议就近沿用 V11 的 utf8mb4_unicode_ci、BIGINT UNSIGNED 自增主键、DATETIME(3)、uk_/idx_ 命名、中文注释、服务层维护关联且不新增外键。现有框架主键是有符号 BIGINT，引用它的 skill_id 仍用 BIGINT。不改框架列名、source 或现有外键。

## 功能对照

| 设计功能 | 当前支撑 | 缺口与建议 |
| --- | --- | --- |
| 上传并检查包 | SkillDocuments 有大小、路径、文件数量和文本解析校验 | 已有技术校验，缺少可追溯检查报告；导入批次保存报告及检查器版本 |
| 同名识别、入口处理与内容策略 | importDocument 遇同名返回 409 | 保存目标名称、入口策略、内容策略；目标必须重新校验；不能静默覆盖 |
| 原包版本与平台发布版本分别展示 | metadata_json 可容纳任意文本，但当前 newRow 只生成 name/description | 单独保存 package_version 和 platform_version，避免混用 |
| 导入草稿 | agentscope_skills.status 只有启用/停用 | 草稿状态与启停状态分开；先写版本表，发布前不进入运行表 |
| 已建议的工具映射 | agentscope_skill_resources 是路径/文本资料；ai_agent_skill 是 Agent/Skill 绑定 | 两者都不表示源工具到目标 MCP 工具的映射，新增版本依赖表 |
| 待接入依赖 | 无独立表 | 保存源工具标识、能力要求、映射状态和原因；允许待接入，不丢弃 |
| 原包约束和导入确认 | 正文可以包含文字，但没有结构化规则及确认记录 | 原包规则快照保存在版本，确认人/时间保存在批次；发布时服务端重新校验 |
| 保留原文、脚本和原包 | LONGTEXT 能存文本，不能无损保存任意二进制包；原始上传字节没有入库 | 保存上传原件和 SHA-256；版本文件保留路径、类型及字节；保留脚本不代表执行脚本 |
| 导入结果及后续入口 | 导入返回 Detail，前端只显示文件名提示 | 批次关联版本和结果快照；按批次 ID 查看报告、按版本 ID 查看原包 |
| 失败后重试、重复确认 | 无批次幂等状态 | 请求键唯一；确认导入使用事务及条件状态更新 |

当前服务 importDocument 是解析后直接插入框架表，且同名报错。导入没有新增 Agent 绑定，但启用/停用无法表达“未发布草稿”。不能仅补前端步骤条而保留这种写入语义。

## 建议最小模型：四张表

1. ai_skill_import：一次导入会话。上传原件、校验报告、策略、确认记录、结果快照及幂等键。
2. ai_skill_version：一份可编辑草稿或已发布的内容快照。包版本与平台版本分别保存，已发布版本内容不可原地修改。
3. ai_skill_version_file：版本内文件，按版本和相对路径唯一，支持原文字节。
4. ai_skill_version_dependency：版本内每项源依赖及映射。区分 MCP 工具、平台内置能力、待接入依赖。

不为每一步、每一条提示单独建表。低频且仅随整份报告读取的检查明细放 JSON；需要筛选、唯一校验和关联的状态、标识、版本及映射放独立列。metadata_json 继续承担框架元数据，避免成为所有业务数据的堆积字段。

### 建表字段规格（供后续迁移实现）

所有新表：id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT 主键；gmt_create/gmt_modified DATETIME(3) NOT NULL，由现有 BaseAuditBean/MyBatis-Plus 填充；引擎 InnoDB，字符集 utf8mb4，排序规则 utf8mb4_unicode_ci；每列加中文 COMMENT。不新增 is_deleted：批次和历史按明确保留策略清理，关系物理删除；发布版本应先检查引用再允许清理。

| 表 | 业务字段与建议类型 | 索引 |
| --- | --- | --- |
| ai_skill_import | request_key VARCHAR(64) NOT NULL；created_by BIGINT UNSIGNED NOT NULL；state VARCHAR(24) NOT NULL；filename VARCHAR(255) NOT NULL；package_sha256 CHAR(64) NOT NULL；package_body MEDIUMBLOB NULL；target_name VARCHAR(64) NULL；entry_policy VARCHAR(32) NULL；content_policy VARCHAR(32) NULL；check_report_json JSON NULL；checker_version VARCHAR(64) NULL；confirmed_by BIGINT UNSIGNED NULL；confirmed_at DATETIME(3) NULL；result_version_id BIGINT UNSIGNED NULL；result_json JSON NULL；error_code VARCHAR(64) NULL；expires_at DATETIME(3) NULL；revision BIGINT UNSIGNED NOT NULL DEFAULT 0 | UNIQUE uk_ai_skill_import_request(created_by,request_key)；idx_ai_skill_import_actor_time(created_by,gmt_create)；idx_ai_skill_import_state_expiry(state,expires_at) |
| ai_skill_version | skill_name VARCHAR(64) NOT NULL；skill_id BIGINT NULL；version_no INT UNSIGNED NOT NULL；package_version VARCHAR(64) NULL；platform_version VARCHAR(64) NULL；state VARCHAR(24) NOT NULL；description TEXT NOT NULL；skill_content LONGTEXT NOT NULL；metadata_json JSON NULL；source_rules_json JSON NULL；visible_scope CHAR(1) NOT NULL DEFAULT '0'；owner_dept_id BIGINT UNSIGNED NULL；created_by BIGINT UNSIGNED NOT NULL；import_id BIGINT UNSIGNED NULL；published_by BIGINT UNSIGNED NULL；published_at DATETIME(3) NULL；revision BIGINT UNSIGNED NOT NULL DEFAULT 0 | UNIQUE uk_ai_skill_version_name_no(skill_name,version_no)；UNIQUE uk_ai_skill_version_platform(skill_name,platform_version)；UNIQUE uk_ai_skill_version_import(import_id)；idx_ai_skill_version_skill_state(skill_id,state) |
| ai_skill_version_file | version_id BIGINT UNSIGNED NOT NULL；resource_path VARCHAR(500) NOT NULL；file_kind VARCHAR(24) NOT NULL；content LONGBLOB NOT NULL；content_sha256 CHAR(64) NOT NULL；byte_size INT UNSIGNED NOT NULL | UNIQUE uk_ai_skill_version_file_path(version_id,resource_path) |
| ai_skill_version_dependency | version_id BIGINT UNSIGNED NOT NULL；source_key VARCHAR(128) NOT NULL；capability_key VARCHAR(128) NULL；required_flag CHAR(1) NOT NULL DEFAULT '1'（0 可选 / 1 必需）；target_kind VARCHAR(24) NOT NULL；target_provider_key VARCHAR(128) NULL；target_tool_key VARCHAR(128) NULL；mapping_state VARCHAR(24) NOT NULL；reason VARCHAR(1000) NULL；target_snapshot_json JSON NULL | UNIQUE uk_ai_skill_version_dep_source(version_id,source_key)；idx_ai_skill_version_dep_target(target_provider_key,target_tool_key) |

对应建表脚本已整理为 `ag-ui/src/main/resources/db/mysql/V12__skill_import_version.sql`，包含表、字段、状态值及关联约束的中文注释，尚未执行。MEDIUMBLOB 方案适用于目前 2MB 上传上限，避免为这一步引入对象存储；规模扩大时可迁移成对象存储引用，原件表与文件表的保留策略必须一起制定。原件到期清理后要明确显示“原包已清理”，不可继续显示可下载。

## 状态和关键约束

- import.state：UPLOADED / CHECKED / CONFIRMED / FAILED / CANCELLED / EXPIRED。当前页面步骤可由状态和已保存字段推导，无须独立步骤表。检查失败报告也应保留，不能与业务写入一起回滚丢失。
- version.state：DRAFT / PUBLISHED / ARCHIVED。不能复用现有 status='1' 来代表草稿；现有 status 仍是启停。
- dependency.target_kind：MCP_TOOL / PLATFORM / UNRESOLVED；mapping_state：SUGGESTED / CONFIRMED / PENDING。PENDING 必须允许目标为空；CONFIRMED 的目标必须与 target_kind 一致。
- 原包规则版本化，检查报告中每条规则应有稳定 rule_key、级别、结果、说明；用户确认不等于规则通过。发布时按原包规则重新核验必需依赖，不能把所有待接入依赖一律忽略，也不能仅按有无工具数量判定。
- MCP 当前没有对应的数据库目录表（本仓库迁移中未发现），因此 provider/tool 使用稳定业务键，并在发布时查询真实注册目录核验，不能把模拟前端 ID 当作已持久化关联。若后续数据库化 MCP 目录，迁移为真实 ID 关联。
- ai_agent_skill 目前只绑定 skill_id。上述模型支持导入和当前版本投影；若扩展到 Figma Agent 固定 Skill 版本，还需增加 skill_version_id 并改造运行时解析，不能宣称现有绑定已支持版本固定。

## 写入流程与迁移顺序

1. 上传：创建批次，保存原件摘要、检查报告；未确认前不写 agentscope_skills。检查前后保留已有路径、大小、数量、编码验证。
2. 检查与策略：保存显式选择；选择改变后旧检查结果和映射须标为待重新检查。采用 revision 条件更新防止覆盖他人的修改。
3. 资源与规则：保存依赖及映射，确认人/确认时间由服务端取当前登录用户，不能信任客户端传值。
4. 确认导入：同一事务锁定/条件更新批次，创建 DRAFT 版本、文件和依赖，保存结果版本 ID 与结果快照；重复请求返回同一个结果，不生成重复版本。版本号分配依靠同名串行控制及唯一键冲突重试，不能直接无保护 MAX+1。
5. 发布：校验依赖、规则和权限后，在同一事务更新现有框架正文/资料、版本状态和发布记录；按技能名串行化。框架新技能首次发布才创建 skill_id，已有技能更新时保留 id、source、created_at 与 Agent 绑定。草稿原包文件与供运行时加载的文件必须区分，运行表只投影受支持的文件。
6. 存量：将每个当前技能初始化成一个历史基线发布版本；没有证据的原包版本留空，不伪造原件。先确认当前真实库结构与备份，再制作下一号手工迁移。应用启动不自动执行本方案。

## 验收条件

- 上传和保存草稿后，当前聊天的技能内容及绑定不变；刷新可继续导入流程。
- 同名处理和内容策略可回显；当前直接 409 的行为得到明确替换，不能静默覆盖。
- 检查不通过能查到报告；重试确认只返回一个草稿版本。
- 原包、解析文件、版本号、处理方式和待接入原因能从记录复现，原件摘要可核对。
- 工具映射和平台能力可区分；发布时目标消失或必需规则不满足会阻断。
- 发布事务中断不出现一半新正文、一半旧资料；并发发布不产生混合版本。
- 存量 Skill 的 id/source、可见性、启停和 Agent 绑定均保留；历史版本查看不会修改运行表。

源代码证据：ag-ui/src/main/resources/db/mysql/V6__agent_skill.sql、V11__skill_governance.sql、V7__app_directory.sql；ag-ui/src/main/java/com/beyondzy/demo/service/skill/SkillDocuments.java；service/skill/impl/SkillAdminServiceImpl.java；domain/BaseAuditBean.java。
