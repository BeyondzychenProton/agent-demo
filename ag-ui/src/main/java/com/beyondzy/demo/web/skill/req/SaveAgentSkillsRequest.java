package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 覆盖 session-chat 的技能绑定。空列表表示全部解绑。 */
@Schema(description = "覆盖 session-chat 的技能绑定，空列表表示全部解绑。")
public record SaveAgentSkillsRequest(@Schema(description = "技能名。未指定版本时使用当前生效版本") List<String> names, @Schema(description = "指定版本的绑定") List<Binding> bindings) {
    public record Binding(@Schema(description = "技能名") String name, @Schema(description = "固定的已发布版本 id") String versionId) {}
}
