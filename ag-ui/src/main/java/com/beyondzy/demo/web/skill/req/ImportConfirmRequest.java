package com.beyondzy.demo.web.skill.req;

import java.util.List;

/** 确认导入。同名技能必须显式确认，ERROR 规则不能靠这个列表绕过。 */
public record ImportConfirmRequest(String revision, boolean acknowledgeExisting, List<String> acknowledgedRuleKeys) {}
