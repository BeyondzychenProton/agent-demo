package com.beyondzy.demo.web.skill.req;

/** 保存导入目标、入口策略、内容策略和可见范围。 */
public record ImportConfigRequest(
        String revision,
        String targetName,
        String entryPolicy,
        String contentPolicy,
        String visibleScope,
        Long ownerDeptId) {}
