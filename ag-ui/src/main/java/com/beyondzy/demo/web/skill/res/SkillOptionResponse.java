package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.service.skill.view.SkillAdminView;
import java.util.List;

/** 绑定页可勾选的技能。只包含当前操作人可见且启用的技能。 */
@Schema(description = "绑定页可勾选的技能。")
public record SkillOptionResponse(@Schema(description = "技能名") String name, @Schema(description = "技能说明") String description, @Schema(description = "当前生效版本 id") String currentVersionId,
        @Schema(description = "可绑定的已发布版本") List<SkillAdminView.VersionOption> versions) {}
