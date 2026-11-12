package com.beyondzy.demo.web.skill.res;

import java.util.List;

/** 技能分页。 */
public record SkillPageResponse(List<SkillSummaryResponse> items, long total, int page, int size) {}
