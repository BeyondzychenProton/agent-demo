package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 创建或修改技能。修改时路径里的名字为准，这里的 name 不会改掉已有技能名。 */
@Schema(description = "创建或修改技能。")
public record SaveSkillRequest(
        @Schema(description = "技能名") String name,
        @Schema(description = "技能说明") String description,
        @Schema(description = "技能正文") String content,
        @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
        @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId,
        @Schema(description = "0 启用，1 停用") String status,
        @Schema(description = "参考资料") List<SkillFileRequest> files) {
    public record SkillFileRequest(@Schema(description = "包内相对路径") String path, @Schema(description = "文件正文") String content) {}
}
