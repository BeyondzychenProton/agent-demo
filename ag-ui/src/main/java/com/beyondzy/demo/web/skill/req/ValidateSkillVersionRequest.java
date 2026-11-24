package com.beyondzy.demo.web.skill.req;

/** 发布前检查。只计算，不改变版本状态。 */
public record ValidateSkillVersionRequest(String revision) {}
