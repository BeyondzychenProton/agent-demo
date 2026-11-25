package com.beyondzy.demo.web.skill.res;


import io.swagger.v3.oas.annotations.media.Schema;

/** 技能附属文件。content 仅在 includeBodies=true 时返回。 */
@Schema(description = "技能附属文件。")
public record SkillResourceResponse(@Schema(description = "包内相对路径") String path, @Schema(description = "资源种类") String kind,
        @Schema(description = "资源正文，仅在包含正文时返回") String content) {}
