package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import com.beyondzy.demo.service.skill.SkillImportService;
import java.util.List;

/** 保存草稿。原包规则、包版本和归属人不在这里改。 */
@Schema(description = "保存草稿。")
public record UpdateSkillVersionRequest(
        @Schema(description = "乐观锁版本号") String revision,
        @Schema(description = "技能说明") String description,
        @Schema(description = "技能正文") String content,
        @Schema(description = "0 公开，1 本部门，2 仅本人") String visibleScope,
        @Schema(description = "本部门可见时的部门来源 id") Long ownerDeptId,
        @Schema(description = "依赖映射") List<SkillImportService.DependencyInput> dependencies,
        @Schema(description = "草稿文件") List<UpdateFile> files,
        @Schema(description = "版本展示名称") String displayName) {
    public record UpdateFile(@Schema(description = "已有文件 id，新文件为空") String fileId, @Schema(description = "包内相对路径") String path, @Schema(description = "文件正文") String content) {}
}
