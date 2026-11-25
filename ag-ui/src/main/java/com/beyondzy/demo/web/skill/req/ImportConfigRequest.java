package com.beyondzy.demo.web.skill.req;


import io.swagger.v3.oas.annotations.media.Schema;

/** 保存导入目标、入口策略、内容策略和可见范围。 */
@Schema(description = "保存导入目标、入口策略、内容策略和可见范围。")
public record ImportConfigRequest(
        @Schema(description = "乐观锁版本号") String revision,
        @Schema(description = "目标技能名") String targetName,
        @Schema(description = "入口处理策略代码") String entryPolicy,
        @Schema(description = "内容处理策略代码") String contentPolicy,
        @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
        @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId) {}
