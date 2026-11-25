package com.beyondzy.demo.web.skill.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 确认导入。同名技能必须显式确认，ERROR 规则不能靠这个列表绕过。 */
@Schema(description = "确认导入。")
public record ImportConfirmRequest(@Schema(description = "乐观锁版本号") String revision, @Schema(description = "同名技能已存在时必须显式确认") boolean acknowledgeExisting, @Schema(description = "已确认的检查规则标识") List<String> acknowledgedRuleKeys) {}
