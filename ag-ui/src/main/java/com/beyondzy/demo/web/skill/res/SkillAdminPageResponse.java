package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 管理端技能列表。不含 SKILL.md 正文。 */
@Schema(description = "管理端技能列表。不含 SKILL.md 正文。")
public record SkillAdminPageResponse(@Schema(description = "当前页技能") List<Item> items, @Schema(description = "总条数") long total, @Schema(description = "页码，从 1 开始") int page, @Schema(description = "每页条数") int size) {
    public record Item(
            @Schema(description = "技能名") String name,
            @Schema(description = "技能说明") String description,
            @Schema(description = "0 启用，1 停用") String status,
            @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
            @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId,
            @Schema(description = "部门名称") String ownerDeptName,
            @Schema(description = "资源数量") int resourceCount,
            @Schema(description = "是否绑在 session-chat 上") boolean bound,
            @Schema(description = "更新时间") LocalDateTime updatedAt) {}
}
