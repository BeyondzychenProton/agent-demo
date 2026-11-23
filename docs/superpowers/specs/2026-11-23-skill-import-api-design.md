# Skill 包导入与版本管理接口设计

状态：接口设计，尚未实现。日期：2026-11-23。

范围：上传 → 检查与策略 → 资源与规则 → 确认生成草稿 → 原包/结果查看 → 发布。依据已确认的数据库方案和 V12__skill_import_version.sql，不扩展 MCP 管理、通用审计、Agent 固定版本等功能。

## 1. 与现有接口的关系

保留 `/api/admin/skills` 列表、详情、options、启停、Agent 绑定作为当前运行技能接口。新增批次资源 `/api/admin/skill-imports` 和版本资源 `/api/admin/skill-versions`，避免与现有 `/{name}` 路由冲突。

现有 `POST /api/admin/skills/import` 会直接写运行表，不能在新版页面中继续使用。新页面切换完成后该接口返回 410，响应包含 `code=LEGACY_IMPORT_DISABLED`，提示使用批次接口；不在原接口静默改响应结构。

现有直接新增、编辑接口在接入版本发布后也必须关闭写入口（410），并提供本设计的草稿新增/编辑入口，否则仍能绕过发布检查；已存在的页面须一起切换。启停保留，发布已有技能时保持原启停状态，新技能首次发布默认停用；发布与启用是两个独立操作。当前绑定仍指向 Skill，发布会更新其运行内容，不承诺 Agent 固定版本。

旧 `DELETE /api/admin/skills/{name}` 对存在版本历史的技能返回 409 VERSION_HISTORY_EXISTS，管理员可使用停用操作；服务检查与删除须按技能串行化，防止并发创建版本留下悬空引用。本次不新增历史清理或强制删除接口。

## 2. 通用约定

- 复用 `SkillAdminAccess.requireAdmin`，未登录 401、非管理员 403。所有接口鉴权后执行；所有管理员可以查看版本，导入批次仅创建人可读写，其他管理员访问返回 404。下载同样检查权限。
- JSON 成功响应直接返回资源，不增加 code/data 包装；集合使用 `{items,total,page,size}`，page 从 1 开始，size 默认 20，范围 1～100。新接口 BIGINT 标识和 revision 使用十进制字符串，避免 JavaScript 精度丢失。
- 普通成功读取、更新、确认、发布为 200；创建批次/手工草稿为 201，带 Location；取消成功返回更新后的批次；文件下载返回字节。
- 错误保留既有 `error` 文本，并增加 `code`、可选 `details`：`{"error":"记录已被修改，请刷新后重试","code":"REVISION_CONFLICT","details":{"currentRevision":"4"}}`。需扩展现有异常处理器，旧客户端仍可读 error。
- 所有可编辑请求携带 revision，使用 `UPDATE ... WHERE id=? AND revision=?` 条件更新并递增；失败返回 409，禁止最后写入覆盖。
- 新接口时间统一输出带偏移的 ISO 8601；DATETIME(3) 按 UTC 写入/读取并显式序列化为 Z，不能依赖机器默认时区。只适用于新表，不重新解释旧表时间。
- 所有字段取值由后端枚举统一定义，不接受自由字符串。确认人、创建人、发布时间、摘要及检查结果由服务端生成。日志和快照不保存认证秘密。

## 3. 接口清单

| 方法与路径 | 用途 | 写入 |
| --- | --- | --- |
| POST /api/admin/skill-imports | 上传并创建批次 | import |
| GET /api/admin/skill-imports | 当前管理员的批次列表；state/page/size | 无 |
| GET /api/admin/skill-imports/{id} | 恢复步骤、检查报告、映射、导入结果 | 无 |
| PUT /api/admin/skill-imports/{id}/configuration | 保存目标、策略、可见范围并重新检查 | import |
| GET /api/admin/skill-resource-options | 查询当前可映射的真实工具或平台能力 | 无 |
| PUT /api/admin/skill-imports/{id}/dependencies | 整组保存依赖映射 | import 暂存 |
| POST /api/admin/skill-imports/{id}/confirm | 确认并生成唯一草稿版本 | import/version/file/dependency |
| POST /api/admin/skill-imports/{id}/cancel | 取消未完成批次 | import |
| GET /api/admin/skill-imports/{id}/source | 下载原始上传文件 | 无 |
| POST /api/admin/skill-versions | 从当前技能或手工创建草稿 | version/file/dependency |
| GET /api/admin/skill-versions | 按 skillName/state/page/size 查询历史和草稿 | 无 |
| GET /api/admin/skill-versions/{id} | 版本正文、文件清单、依赖和规则 | 无 |
| PUT /api/admin/skill-versions/{id} | 编辑草稿内容及映射 | version/file/dependency |
| GET /api/admin/skill-versions/{id}/files/{fileId} | 查看或下载版本原始文件 | 无 |
| POST /api/admin/skill-versions/{id}/validate | 只校验发布条件，返回阻断项 | 无 |
| POST /api/admin/skill-versions/{id}/publish | 重新校验并发布 | version + 框架运行表 |

不提供物理删除历史、任意上传脚本执行、批量发布或后台异步任务接口。本阶段包体较小，解析/检查同步完成，JDBC 使用现有 boundedElastic，不阻塞 WebFlux 事件线程。

## 4. 上传与批次资源

`POST /api/admin/skill-imports`：multipart/form-data，必填 `file`；请求头 `Idempotency-Key` 为 1～64 字符。按 `(created_by, request_key)` 幂等。相同键、相同上传字节和文件名返回原资源 200；相同键不同输入返回 409 IDEMPOTENCY_CONFLICT。首次创建 201。

接收流时限制大小，不能先无上限 join 再校验。MD 上限 256KiB、ZIP 上限 2MiB；解压文件最多 50 个、单参考文件 512KiB、解压总量 2MiB。技术校验保留既有路径和编码约束；支持 ASSET 二进制需显式扩展解析器，不能仅因数据库可存就放宽当前支持类型。文件类型不支持 415，超限 413，请求缺字段 400；此类接收失败不创建批次。

已接收且满足体积上限后，包内容错误也保留批次和检查报告，返回 201，state=FAILED。默认策略尚未确认时为 UPLOADED；配置检查完成后为 CHECKED，是否可确认必须看 canConfirm 和阻断项，不能只看状态。

批次详情示例（标识仅作示例）：

```json
{
  "id": "101", "revision": "2", "state": "CHECKED", "step": "RESOURCES",
  "filename": "meeting-booking.zip", "packageSha256": "<64位小写十六进制>",
  "sourceAvailable": true, "expiresAt": "2026-11-24T08:00:00.000Z",
  "configuration": {
    "targetName": "meeting-booking", "entryPolicy": "KEEP_ORIGINAL",
    "contentPolicy": "PRESERVE_PACKAGE", "visibleScope": "0", "ownerDeptId": null
  },
  "package": {"name": "meeting-booking", "version": "1.0.1", "fileCount": 2},
  "checks": [{"ruleKey": "PACKAGE_STRUCTURE", "level": "ERROR", "result": "PASS", "message": "包结构通过"}],
  "dependencies": [{
    "sourceKey": "meeting.reserve", "capabilityKey": "meeting.write", "required": true,
    "targetKind": "MCP_TOOL", "targetProviderKey": "mantianxing",
    "targetToolKey": "create_meeting", "mappingState": "SUGGESTED", "reason": null
  }],
  "canConfirm": false, "blockers": [{"code": "MAPPING_NOT_CONFIRMED", "sourceKey": "meeting.reserve"}],
  "result": null
}
```

列表仅返回摘要，不返回正文、原件、完整 JSON 报告。step 为服务端推导的 FILE / REVIEW / RESOURCES / RESULT。过期检查在每次读写时按服务时间执行，不依赖定时任务及时运行；超时返回 410 IMPORT_EXPIRED。已确认不再按未完成期限过期。

## 5. 检查与策略

`PUT .../{id}/configuration` 请求：

```json
{"revision":"2","targetName":"meeting-booking","entryPolicy":"KEEP_ORIGINAL","contentPolicy":"PRESERVE_PACKAGE","visibleScope":"0","ownerDeptId":null}
```

- entryPolicy：KEEP_ORIGINAL 保留原包入口；PLATFORM_ENTRY 生成平台入口（保留原文文件）。contentPolicy：PRESERVE_PACKAGE 完整保留；REFERENCE_ONLY 转为参考资料。具体组合必须按原包规则校验，不允许丢掉约束；不支持的转换返回 422 POLICY_UNSUPPORTED，不生成伪造的入口。
- targetName 沿用现有名称正则。新名称创建技能；同名时只新增草稿版本，响应提示 `targetExists=true`，不覆盖运行内容。操作者必须显式确认 `acknowledgeExisting=true` 才能确认导入至该名称。
- visibleScope 使用现有 0/1/2；选本部门时 ownerDeptId 必须明确且有效（可由界面预填），禁止静默换成另一个部门。
- 配置变更从原件重新检查，清除旧的工具映射确认、规则确认和导入结果，增加 revision；已确认批次不可修改。可修复的内容失败批次可重新配置；结构损坏只能新上传。
- 返回更新后的完整批次。CHECKED 仅表示检查已完成，报告 ERROR/FAIL 仍会阻止确认；WARN 可按要求确认后继续。

## 6. 工具目录与依赖映射

`GET /api/admin/skill-resource-options?kind=MCP_TOOL&q=meeting&page=1&size=20` 返回 `{items,total,page,size}`，item 字段：targetKind、providerKey、toolKey、displayName、capabilityKeys、available。目录来自真实注册/发现结果和平台能力注册表，不读取前端 mock 数据；没有成功发现的工具不作为可确认候选。查询不执行预约等业务工具。发现服务不可用返回 503 RESOURCE_CATALOG_UNAVAILABLE。

`PUT .../{id}/dependencies`：

```json
{"revision":"3","items":[
  {"sourceKey":"meeting.reserve","targetKind":"MCP_TOOL","targetProviderKey":"mantianxing","targetToolKey":"create_meeting","mappingState":"CONFIRMED"},
  {"sourceKey":"calendar.get_freebusy","targetKind":"UNRESOLVED","targetProviderKey":null,"targetToolKey":null,"mappingState":"PENDING","reason":"服务尚未接入"}
]}
```

整组覆盖，必须包含全部解析得到的依赖且 sourceKey 不重复；不能通过删掉必需项绕过校验。required/capabilityKey/原包规则由服务端解析，客户端不能改。CONFIRMED 映射必须真实存在、可用且满足能力要求；MCP 工具 provider/tool 必填，平台能力 provider 为空、tool 为平台能力键；UNRESOLVED 必须 PENDING 且目标为空、原因必填。返回更新后的批次和 canConfirm/blockers。

待接入不必然阻止保存草稿；有原包规则要求当场绑定时阻止导入确认，否则在发布时阻断。canConfirm 与 canPublish 必须分别计算，不能混用。

## 7. 确认、取消和原包

`POST .../{id}/confirm` 请求：`{"revision":"4","acknowledgeExisting":true,"acknowledgedRuleKeys":["PENDING_DEPENDENCIES"]}`。规则标识来自最新检查报告；只认可需要确认的 WARN，ERROR 不可通过确认绕过。规则确认写入批次报告的 confirmations 子对象。

事务中锁批次、复核归属/期限/revision/策略/报告，创建一个 DRAFT 版本及文件/依赖，写 confirmedBy/confirmedAt/resultVersionId/resultJson，批次转 CONFIRMED。相同批次已成功确认后，重试直接返回原结果，不因旧 revision 再生成版本；前端不能用该重试修改已确认选项。响应为完整批次，其中：

```json
{"result":{"versionId":"201","skillName":"meeting-booking","versionState":"DRAFT","packageVersion":"1.0.1","platformVersion":null,"retainedFileCount":2,"pendingDependencyCount":1,"processingMode":"PRESERVE_PACKAGE","warnings":["仍有待接入依赖"]}}
```

确认失败整体回滚草稿及子表，保留此前批次和报告，返回明确错误；不把基础设施异常伪装成确认成功。重复确认幂等以批次 ID 为键，无需另建确认请求表。

`POST .../{id}/cancel` 请求 `{"revision":"4"}`：仅未确认批次可取消；已取消重复调用返回同一资源；已确认返回 409 IMPORT_ALREADY_CONFIRMED。暂不立即删除原件，按保留策略清理。

`GET .../{id}/source` 下载原始上传字节，安全编码 Content-Disposition 文件名，设置 `nosniff`；原件清理后 410 SOURCE_PURGED，详情 sourceAvailable=false。下载不把二进制包塞进 JSON。

## 8. 草稿与版本接口

`POST /api/admin/skill-versions` 从当前技能创建：`{"skillName":"meeting-booking","baseVersionId":"201"}`。baseVersionId 必须是该技能的已发布版本；手工新建可省略 baseVersionId，同时必填 description/content/visibleScope/ownerDeptId/files。创建响应 201；复制不改变原归属，created_by 在当前四表模型下同时承载仅本人归属，复制已有仅本人技能时须保留该归属，不能让管理员复制就转移可见权。该接口暂不提供自动重试，前端超时后查询草稿列表确认，避免误建多个草稿。

`GET /api/admin/skill-versions?skillName=meeting-booking&state=DRAFT&page=1&size=20` 返回摘要列表，含 id/skillName/versionNo/packageVersion/platformVersion/state/publishedAt/revision；GET `/{id}` 返回完整可编辑内容和规则/依赖，但文件只返回 id/path/kind/byteSize/sha256/textPreviewAvailable。

`PUT /api/admin/skill-versions/{id}` 完整保存请求：revision、description、content（纯正文）、visibleScope、ownerDeptId、dependencies 和 files；files 项为 `{fileId,path,content}`，已有文件省略 content 表示保留字节，新文本文件必须传 content，移除项表示删除。fileId 必须属于该版本；二进制文件只允许保留，不提供任意字节编辑。SKILL 原文快照不可通过此入口替换，content 经统一合成器生成拟发布 SKILL.md。sourceRules、packageVersion、importId、归属人、原包入口文件由服务端维护，客户端不可修改。

仅 DRAFT 可更新；保存后返回版本详情及最新 revision。配置转换或正文变更必须重跑规则校验，发布时仍重新校验。原包快照和拟发布内容分离：file 表保存原包入口，version.skill_content 保存拟发布入口；参考文本编辑只改变草稿版本文件，不修改上传原件。

`GET /api/admin/skill-versions/{id}/files/{fileId}?download=false`：校验文件所属版本。可安全预览的 UTF-8 文本返回 text/plain，二进制一律 attachment；download=true 均以附件下载。文件名编码并使用 nosniff，不直接渲染上传 HTML/SVG。

## 9. 发布校验与发布

`POST .../{id}/validate` 请求 `{"revision":"3"}`，返回 `{versionId,revision,canPublish,checks,blockers,warnings}`。只计算，不更新版本状态；检查原包规则、必需依赖、目录可用性、内容及引用完整性。此结果不能作为发布授权凭据。

`POST .../{id}/publish` 请求：`{"revision":"3","platformVersion":"1.3.2","acknowledgedRuleKeys":[]}`。

- 发布时重新检查全部条件，校验平台版本格式与技能内唯一性、目标能力及权限；外部发现失败返回 503，不把未知当成功。运行时目录使用的验证结果/代次在提交前重验，后续工具运行失败仍按运行时错误处理，不能保证外部资源永久有效。
- 同技能发布串行化：已有技能锁框架行，新技能依靠唯一名称冲突及事务重试；各版本也条件锁定。顺序为锁定/复核 → 更新框架正文及受支持资料 → 写发布状态，任一步失败整体回滚。
- 新技能创建时 status='1'（停用）、source 固定既有框架值；已有技能保留 id/source/created_at/status/绑定，更新版本要求的正文、用途和可见范围。必须保留仅本人归属语义，不可覆盖为发布操作人。
- 已发布版本对相同 platformVersion 重试返回既有成功结果，不再次投影运行表；即使后面已有新版本也不回退内容。不同 platformVersion 返回 409 VERSION_ALREADY_PUBLISHED。
- 返回 `{versionId,skillName,state:"PUBLISHED",platformVersion,publishedAt,runtimeStatus}`；不自动启用或新增 Agent 绑定。当前表未持久化运行版本指针，不能仅靠历史发布时间声称某个版本当前生效；当前内容以运行表为准。

## 10. 暂存与四表的映射

确认前没有 version_id，不能提前往依赖子表写无主记录。为保持已确认的四表模型，导入中间态统一暂存于 `ai_skill_import.check_report_json` 的结构化文档：

```json
{"schemaVersion":1,"checks":[],"normalizedPackage":{},"placement":{"visibleScope":"0","ownerDeptId":null},"dependencyDrafts":[],"confirmations":[]}
```

targetName/entryPolicy/contentPolicy 仍使用独立列，禁止 JSON 重复保存另一份真值。checks 和 normalizedPackage 由服务端生成；placement 和 dependencyDrafts 经验证后保存。更新报告与 revision 必须原子完成。确认时把快照转换成 version/file/dependency 行，resultJson 只保存确认时结果摘要，详情中工具后续健康状态另行计算。

## 11. 错误码与实现验收

| HTTP | 业务码举例 | 处理 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT / INVALID_NAME | 修正参数 |
| 401/403 | UNAUTHENTICATED / ADMIN_REQUIRED | 登录或检查权限 |
| 404 | IMPORT_NOT_FOUND / VERSION_NOT_FOUND / FILE_NOT_FOUND | 不存在或不可见 |
| 409 | REVISION_CONFLICT / IDEMPOTENCY_CONFLICT / VERSION_CONFLICT / IMPORT_ALREADY_CONFIRMED / VERSION_ALREADY_PUBLISHED | 刷新或展示冲突 |
| 410 | IMPORT_EXPIRED / SOURCE_PURGED / LEGACY_IMPORT_DISABLED | 新上传或切换入口 |
| 413/415 | UPLOAD_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE | 修正上传文件 |
| 422 | CHECK_BLOCKED / DEPENDENCY_UNAVAILABLE / POLICY_UNSUPPORTED | 显示规则与依赖详情 |
| 503 | RESOURCE_CATALOG_UNAVAILABLE | 保留输入，稍后重试 |

实现顺序：批次/报告和上传 → 配置及真实目录读取 → 依赖暂存与幂等确认 → 草稿查看编辑 → 发布事务 → 前端切换并关闭旧直接写接口。存量基线迁移先完成，才允许旧技能通过版本入口编辑。

验收需覆盖：管理员/批次归属隔离、上传超限在聚合前拒绝、损坏包报告留存、刷新恢复、并发 revision 冲突、同键不同包冲突、确认重试不重复建版本、待接入可保存但按规则阻断发布、原件下载权限与清理状态、跨版本 fileId 拒绝、发布中断整体回滚、并发发布不混合内容、历史发布重试不覆盖更新版本、旧写接口无法绕过检查、仅本人归属不因管理员操作转移。

实现证据：现有 SkillAdminController/SkillAdminAccess/SkillExceptionHandler、SaveSkillRequest、SkillDocuments；本文件中的新路由和扩展响应均为待实现契约。
