package com.beyondzy.demo.service.skill.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 版本详情。文件只给清单，正文是去掉 frontmatter 的纯文本。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillVersionView(
        String id,
        String revision,
        String skillName,
        String skillId,
        String versionNo,
        String packageVersion,
        String platformVersion,
        String state,
        String description,
        String content,
        String visibleScope,
        Long ownerDeptId,
        String importId,
        String publishedAt,
        List<FileItem> files,
        List<SkillImportView.Dependency> dependencies,
        List<SkillImportView.Check> checks,
        String displayName) {

    public record FileItem(
            String id, String path, String kind, int byteSize, String sha256, boolean textPreviewAvailable) {}

    public record Summary(
            String id,
            String skillName,
            String versionNo,
            String packageVersion,
            String platformVersion,
            String state,
            String publishedAt,
            String revision) {}

    public record FileBody(String filename, byte[] body, boolean text, boolean attachment) {}

    public record Validation(
            String versionId,
            String revision,
            boolean canPublish,
            List<SkillImportView.Check> checks,
            List<SkillImportView.Blocker> blockers,
            List<String> warnings) {}

    public record Published(
            String versionId,
            String skillName,
            String state,
            String platformVersion,
            String publishedAt,
            String runtimeStatus) {}
}
