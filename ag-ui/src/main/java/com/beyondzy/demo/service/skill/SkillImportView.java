package com.beyondzy.demo.service.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 导入批次接口的资源形状。标识和 revision 用十进制字符串。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillImportView(
        String id,
        String revision,
        String state,
        String step,
        String filename,
        String packageSha256,
        boolean sourceAvailable,
        String expiresAt,
        Configuration configuration,
        @JsonProperty("package") PackageInfo packageInfo,
        List<Check> checks,
        List<Dependency> dependencies,
        boolean canConfirm,
        boolean targetExists,
        List<Blocker> blockers,
        Result result) {

    public record Configuration(
            String targetName, String entryPolicy, String contentPolicy, String visibleScope, Long ownerDeptId) {}

    public record PackageInfo(String name, String version, int fileCount) {}

    public record Check(String ruleKey, String level, String result, String message, boolean acknowledge) {}

    public record Dependency(
            String sourceKey,
            String capabilityKey,
            boolean required,
            String gate,
            String targetKind,
            String targetProviderKey,
            String targetToolKey,
            String mappingState,
            String reason) {}

    public record Blocker(String code, String sourceKey, String ruleKey, String message) {}

    public record Result(
            String versionId,
            String skillName,
            String versionState,
            String packageVersion,
            String platformVersion,
            int retainedFileCount,
            int pendingDependencyCount,
            String processingMode,
            List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(
            String id,
            String revision,
            String state,
            String step,
            String filename,
            String packageSha256,
            boolean sourceAvailable,
            String expiresAt,
            String targetName) {}

    public record Page(List<Summary> items, long total, int page, int size) {}

    public record Created(SkillImportView view, boolean created) {}

    public record SourceFile(String filename, byte[] body) {}
}
