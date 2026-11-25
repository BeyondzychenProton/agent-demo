package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 发布草稿。服务端会重新检查，不接受客户端声明的校验结果。 */
@Schema(description = "发布草稿。")
public record PublishSkillVersionRequest(@Schema(description = "乐观锁版本号") String revision, @Schema(description = "平台发布版本号") String platformVersion, @Schema(description = "已确认的检查规则标识") List<String> acknowledgedRuleKeys) {}
