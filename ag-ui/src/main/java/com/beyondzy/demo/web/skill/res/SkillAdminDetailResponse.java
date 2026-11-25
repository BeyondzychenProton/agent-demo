package com.beyondzy.demo.web.skill.res;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 管理端技能详情。content 是纯正文，files 是参考资料。 */
@Schema(description = "管理端技能详情。content 是纯正文，files 是参考资料。")
public record SkillAdminDetailResponse(
        @Schema(description = "技能名") String name,
        @Schema(description = "技能说明") String description,
        @Schema(description = "剥掉 frontmatter 的正文") String content,
        @Schema(description = "0 启用，1 停用") String status,
        @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
        @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId,
        @Schema(description = "部门名称") String ownerDeptName,
        @Schema(description = "是否绑在 session-chat 上") boolean bound,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "参考资料") List<SkillFileResponse> files) {
    public record SkillFileResponse(@Schema(description = "包内相对路径") String path, @Schema(description = "文件正文") String content) {}
}
