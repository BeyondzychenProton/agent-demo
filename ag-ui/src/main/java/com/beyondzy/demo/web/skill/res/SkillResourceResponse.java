package com.beyondzy.demo.web.skill.res;

/** 技能附属文件。content 仅在 includeBodies=true 时返回。 */
public record SkillResourceResponse(String path, String kind, String content) {}
