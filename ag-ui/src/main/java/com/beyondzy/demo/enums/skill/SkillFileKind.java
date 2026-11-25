package com.beyondzy.demo.enums.skill;


import io.swagger.v3.oas.annotations.media.Schema;

/** 技能包里允许出现的文件种类。 */
@Schema(description = "技能包里允许出现的文件种类。")
public enum SkillFileKind {
    SKILL,
    REFERENCE,
    SCRIPT
}
