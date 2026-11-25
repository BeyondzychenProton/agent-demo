package com.beyondzy.demo.web.skill.req;


import io.swagger.v3.oas.annotations.media.Schema;

/** 发布前检查。只计算，不改变版本状态。 */
@Schema(description = "按当前草稿检查是否可发布。")
public record ValidateSkillVersionRequest(@Schema(description = "乐观锁版本号") String revision) {}
