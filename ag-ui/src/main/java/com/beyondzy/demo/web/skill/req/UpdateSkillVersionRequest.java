package com.beyondzy.demo.web.skill.req;

import com.beyondzy.demo.service.skill.SkillImportService;
import java.util.List;

/** 保存草稿。原包规则、包版本和归属人不在这里改。 */
public record UpdateSkillVersionRequest(
        String revision,
        String description,
        String content,
        String visibleScope,
        Long ownerDeptId,
        List<SkillImportService.DependencyInput> dependencies,
        List<UpdateFile> files,
        String displayName) {
    public record UpdateFile(String fileId, String path, String content) {}
}
