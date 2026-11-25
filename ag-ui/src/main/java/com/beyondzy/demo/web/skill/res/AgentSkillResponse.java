package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 当前唯一智能体和它绑着的技能。 */
@Schema(description = "当前唯一智能体和它绑着的技能。")
public record AgentSkillResponse(@Schema(description = "智能体标识") String agentId, @Schema(description = "智能体名称") String title, @Schema(description = "已绑定的技能") List<BoundSkill> skills) {
    public record BoundSkill(@Schema(description = "技能名") String name, @Schema(description = "技能说明") String description, @Schema(description = "0 启用，1 停用") String status, @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
            @Schema(description = "固定的已发布版本 id") String versionId, @Schema(description = "平台发布版本号") String platformVersion, @Schema(description = "技能内版本序号") String versionNo) {}
}
