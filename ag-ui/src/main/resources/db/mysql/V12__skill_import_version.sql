-- Skill 包导入与版本管理（MySQL 8.0）。手工执行，应用启动不负责建表。
-- 前置：确认 V6__agent_skill.sql、V11__skill_governance.sql 已应用。
-- 本脚本只新增业务表，不修改框架表，不迁移存量数据，不改变当前生效技能。
-- 新业务表沿用 V11：自增主键、DATETIME(3)、utf8mb4_unicode_ci、uk_/idx_ 索引命名。
-- gmt_create/gmt_modified 由 BaseAuditBean / MyBatis-Plus 填充，无数据库默认值。
-- 不新增外键、软删除字段；关联检查、状态转换、权限校验由服务层负责。
-- CREATE TABLE IF NOT EXISTS 仅跳过同名表，不会修复已有表的字段或索引差异。
--
-- 关联：ai_skill_import.result_version_id -> ai_skill_version.id；
--       ai_skill_version.import_id -> ai_skill_import.id；
--       ai_skill_version.skill_id -> agentscope_skills.id（保留框架有符号 BIGINT 类型）；
--       文件和依赖的 version_id -> ai_skill_version.id。
-- created_by/confirmed_by/published_by 均引用 app_user.id，由服务端取当前登录用户。
-- 发布版本不可原地修改内容；确认导入、发布及运行表投影分别需要事务保证。

-- 一次上传及导入会话。保存检查、策略、原件与结果，支持刷新后继续和重复确认幂等。
-- 唯一键按操作人隔离请求键；同一键重试必须校验原包摘要及请求内容一致。
-- package_body 允许为空仅用于清理原件后保留历史；首次上传成功必须有原件。
-- expires_at 表示未完成会话的过期时间，不表示已确认原件的自动删除时间。
-- 检查失败报告须可追溯，不得随草稿写入事务回滚而丢失。
CREATE TABLE IF NOT EXISTS ai_skill_import (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '导入批次主键',
    request_key VARCHAR(64) NOT NULL COMMENT '操作人范围内的上传幂等请求键',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '发起导入的 app_user.id',
    state VARCHAR(24) NOT NULL COMMENT 'UPLOADED 已上传 / CHECKED 检查完成 / CONFIRMED 已确认 / FAILED 失败 / CANCELLED 取消 / EXPIRED 过期',
    filename VARCHAR(255) NOT NULL COMMENT '原始上传文件名，仅用于展示，不作为存储路径',
    package_sha256 CHAR(64) NOT NULL COMMENT '原始上传字节的 SHA-256，统一小写十六进制',
    package_body MEDIUMBLOB NULL COMMENT '原始上传字节；当前业务上限 2MB；清理后为空',
    target_name VARCHAR(64) NULL COMMENT '策略处理后的目标技能名，确认前校验格式和同名冲突',
    entry_policy VARCHAR(32) NULL COMMENT '入口处理策略代码，具体取值由服务枚举统一定义',
    content_policy VARCHAR(32) NULL COMMENT '原文或平台入口等内容处理策略代码，由服务枚举定义',
    check_report_json JSON NULL COMMENT '检查报告快照，含规则标识、级别、结果及说明',
    checker_version VARCHAR(64) NULL COMMENT '生成检查报告的检查器版本，用于解释历史结果',
    confirmed_by BIGINT UNSIGNED NULL COMMENT '确认导入的 app_user.id，未确认时为空',
    confirmed_at DATETIME(3) NULL COMMENT '确认导入时间，未确认时为空',
    result_version_id BIGINT UNSIGNED NULL COMMENT '确认成功生成的 ai_skill_version.id',
    result_json JSON NULL COMMENT '结果快照，含处理方式、保留文件统计、警告及后续操作信息',
    error_code VARCHAR(64) NULL COMMENT '最近一次失败的业务错误码，成功后清空',
    expires_at DATETIME(3) NULL COMMENT '未完成会话过期时间，为空表示未设置',
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，更新时条件比较并递增',
    gmt_create DATETIME(3) NOT NULL COMMENT '批次创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '批次最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skill_import_request (created_by, request_key),
    KEY idx_ai_skill_import_actor_time (created_by, gmt_create),
    KEY idx_ai_skill_import_state_expiry (state, expires_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能包导入批次';

-- 版本内容及规则快照。首次导入的新技能在发布前 skill_id 可以为空。
-- DRAFT 不写运行表；发布时才将受支持的内容投影到 agentscope_skills/resources。
-- package_version 是包声明版本；platform_version 是平台发布版本，两者不可互相覆盖。
-- version_no 是技能范围内的平台内部序号，不要求等于语义版本号。
-- 编号分配须按技能串行控制并处理唯一键冲突，不能无保护地 MAX(version_no)+1。
-- platform_version 未分配时必须存 NULL，不能用空字符串；MySQL 唯一索引允许多个 NULL。
-- import_id 唯一保证一次导入只生成一个版本；手工创建或存量基线版本可以为 NULL。
-- 仅本人可见时以 created_by 为归属人；从既有技能创建版本必须保留原归属语义。
-- 多个历史版本可以是 PUBLISHED；当前运行内容仍以框架表为准，本文未引入版本固定运行能力。
CREATE TABLE IF NOT EXISTS ai_skill_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '技能版本主键',
    skill_name VARCHAR(64) NOT NULL COMMENT '技能逻辑名称，版本间保持稳定',
    skill_id BIGINT NULL COMMENT 'agentscope_skills.id，新技能首次发布前可为空',
    version_no INT UNSIGNED NOT NULL COMMENT '技能范围内内部版本序号，从 1 开始',
    package_version VARCHAR(64) NULL COMMENT '原包声明版本，原包未声明时为空',
    platform_version VARCHAR(64) NULL COMMENT '平台发布版本号，未分配时为空',
    state VARCHAR(24) NOT NULL COMMENT 'DRAFT 草稿 / PUBLISHED 已发布 / ARCHIVED 已归档；与技能启停状态独立',
    description TEXT NOT NULL COMMENT '该版本的技能用途说明',
    skill_content LONGTEXT NOT NULL COMMENT '该版本拟发布的完整 SKILL.md，包含 frontmatter',
    metadata_json JSON NULL COMMENT '该版本的技能元数据快照',
    source_rules_json JSON NULL COMMENT '原包约束规则快照，供导入检查及发布重新核验',
    visible_scope CHAR(1) NOT NULL DEFAULT '0' COMMENT '发布可见范围：0 公开 / 1 本部门 / 2 仅本人',
    owner_dept_id BIGINT UNSIGNED NULL COMMENT 'app_directory_department.source_id，仅本部门可见时使用',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '版本创建人 app_user.id；仅本人范围的归属人',
    import_id BIGINT UNSIGNED NULL COMMENT '来源 ai_skill_import.id，手工或存量基线版本可为空',
    published_by BIGINT UNSIGNED NULL COMMENT '执行发布的 app_user.id，未发布时为空',
    published_at DATETIME(3) NULL COMMENT '发布时间，未发布时为空',
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，草稿更新时条件比较并递增',
    gmt_create DATETIME(3) NOT NULL COMMENT '版本创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '版本最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skill_version_name_no (skill_name, version_no),
    UNIQUE KEY uk_ai_skill_version_platform (skill_name, platform_version),
    UNIQUE KEY uk_ai_skill_version_import (import_id),
    KEY idx_ai_skill_version_skill_state (skill_id, state)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能草稿与发布版本快照';

-- 版本文件保留解析后的原始文件字节；上传包的完整原件在 ai_skill_import 中。
-- resource_path 是规范化后的包内相对路径；服务层拒绝绝对路径、路径穿越及重复路径。
-- 表默认排序规则不区分大小写；大小写不同的同路径文件按冲突处理，不得静默覆盖。
-- file_kind 只是文件分类；SCRIPT 的保留和查看不授予执行权限。
-- 路径与 content 的修改仅允许发生在草稿版本；发布后冻结。
-- byte_size 和摘要由服务端根据实际字节计算，继续执行现有文件大小和总量限制。
CREATE TABLE IF NOT EXISTS ai_skill_version_file (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '版本文件主键',
    version_id BIGINT UNSIGNED NOT NULL COMMENT '所属 ai_skill_version.id',
    resource_path VARCHAR(500) NOT NULL COMMENT '规范化包内相对路径，版本内唯一',
    file_kind VARCHAR(24) NOT NULL COMMENT 'SKILL 入口原文 / REFERENCE 参考资料 / SCRIPT 脚本 / ASSET 其他资源',
    content LONGBLOB NOT NULL COMMENT '文件原始字节，允许空文件，不允许 NULL',
    content_sha256 CHAR(64) NOT NULL COMMENT '文件字节的 SHA-256，统一小写十六进制',
    byte_size INT UNSIGNED NOT NULL COMMENT '文件字节数，由服务端计算',
    gmt_create DATETIME(3) NOT NULL COMMENT '文件记录创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '文件记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skill_version_file_path (version_id, resource_path)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能版本文件快照';

-- 每个源依赖在版本内唯一，保存映射及待接入原因，不复用 Agent/Skill 绑定表。
-- MCP_TOOL 通过 provider/tool 稳定业务键定位；PLATFORM 使用平台能力键作为 target_tool_key。
-- 尚无真实 MCP 目录表，本阶段不使用前端 mock ID，不创建虚假的数据库外键。
-- UNRESOLVED 的目标字段为空；CONFIRMED 不能指向 UNRESOLVED。
-- MCP_TOOL 确认时 provider/tool 均必填；PLATFORM 确认时 tool 必填、provider 为空。
-- PENDING 允许保留未接入依赖，但能否发布必须按 source_rules_json 逐项核验。
-- target_snapshot_json 只作历史解释，不代替发布时检查目标真实存在及权限有效。
CREATE TABLE IF NOT EXISTS ai_skill_version_dependency (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '版本依赖主键',
    version_id BIGINT UNSIGNED NOT NULL COMMENT '所属 ai_skill_version.id',
    source_key VARCHAR(128) NOT NULL COMMENT '原包中的工具或依赖标识，版本内唯一',
    capability_key VARCHAR(128) NULL COMMENT '依赖要求的能力标识，例如 meeting.write',
    required_flag CHAR(1) NOT NULL DEFAULT '1' COMMENT '依赖要求：0 可选 / 1 必需',
    target_kind VARCHAR(24) NOT NULL COMMENT 'MCP_TOOL MCP工具 / PLATFORM 平台能力 / UNRESOLVED 未解析',
    target_provider_key VARCHAR(128) NULL COMMENT '目标 MCP 服务稳定业务键，平台能力或未解析时为空',
    target_tool_key VARCHAR(128) NULL COMMENT '目标工具或平台能力稳定业务键，未解析时为空',
    mapping_state VARCHAR(24) NOT NULL COMMENT 'SUGGESTED 建议映射 / CONFIRMED 已确认 / PENDING 待接入',
    reason VARCHAR(1000) NULL COMMENT '映射说明或待接入原因，不保存认证凭证',
    target_snapshot_json JSON NULL COMMENT '目标展示及配置摘要快照，不含令牌等认证秘密',
    gmt_create DATETIME(3) NOT NULL COMMENT '依赖记录创建时间',
    gmt_modified DATETIME(3) NOT NULL COMMENT '依赖记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skill_version_dep_source (version_id, source_key),
    KEY idx_ai_skill_version_dep_target (target_provider_key, target_tool_key)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能版本依赖与工具映射';
