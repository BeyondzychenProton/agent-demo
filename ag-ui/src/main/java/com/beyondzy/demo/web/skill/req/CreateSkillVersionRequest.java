package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 从已发布版本复制，或手工新建草稿。复制时忽略正文和文件。 */
@Schema(description = "从已发布版本复制，或手工新建草稿。")
public record CreateSkillVersionRequest(
        @Schema(description = "技能名") String skillName,
        @Schema(description = "复制来源的已发布版本 id，手工新建时为空") String baseVersionId,
        @Schema(description = "技能说明") String description,
        @Schema(description = "技能正文") String content,
        @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
        @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId,
        @Schema(description = "参考资料") List<TextFile> files) {
    public record TextFile(@Schema(description = "包内相对路径") String path, @Schema(description = "文件正文") String content) {}
}
