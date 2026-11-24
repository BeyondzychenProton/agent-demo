package com.beyondzy.demo.service.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** 写进版本的原包规则快照。客户端不能改这份内容来绕过发布检查。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillSourceRules {
    public String entryPolicy;
    public String contentPolicy;
    public String packageVersion;
    public List<SkillPackageInspector.SourceDependency> dependencies = new ArrayList<>();
    public List<SkillPackageInspector.SourceRule> rules = new ArrayList<>();
    public boolean baseline;
}
