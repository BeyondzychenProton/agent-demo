package com.beyondzy.demo.service.skill.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** 确认前暂存在导入批次里的检查文档。目标名和两种策略不放这里。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillCheckDocument {
    public int schemaVersion = 1;
    public List<SkillPackageInspector.Check> checks = new ArrayList<>();
    public Normalized normalizedPackage = new Normalized();
    public Placement placement = new Placement();
    public List<DependencyDraft> dependencyDrafts = new ArrayList<>();
    public List<String> confirmations = new ArrayList<>();
    public boolean structural;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Normalized {
        public String name;
        public String version;
        public String description;
        public int fileCount;
        public List<FileRef> files = new ArrayList<>();
        public List<SkillPackageInspector.SourceDependency> dependencies = new ArrayList<>();
        public List<SkillPackageInspector.SourceRule> rules = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileRef {
        public String path;
        public String kind;
        public int byteSize;
        public String sha256;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Placement {
        public String visibleScope;
        public Long ownerDeptId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DependencyDraft {
        public String sourceKey;
        public String capabilityKey;
        public boolean required;
        public String gate;
        public String targetKind;
        public String targetProviderKey;
        public String targetToolKey;
        public String mappingState;
        public String reason;
    }
}
