package com.beyondzy.demo.web.skill.req;

import java.util.List;

/** 发布草稿。服务端会重新检查，不接受客户端声明的校验结果。 */
public record PublishSkillVersionRequest(String revision, String platformVersion, List<String> acknowledgedRuleKeys) {}
