package com.beyondzy.demo.web.skill.req;

import java.util.List;

/** 覆盖 session-chat 的技能绑定。空列表表示全部解绑。 */
public record SaveAgentSkillsRequest(List<String> names, List<Binding> bindings) {
    public record Binding(String name, String versionId) {}
}
