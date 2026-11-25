package com.beyondzy.demo.web.skill.req;
import io.swagger.v3.oas.annotations.media.Schema;


/** 只改启用状态。停用后绑定行留着。 */
@Schema(description = "只改启用状态")
public record SkillStatusRequest(@Schema(description = "是否启用") boolean enabled) {}
