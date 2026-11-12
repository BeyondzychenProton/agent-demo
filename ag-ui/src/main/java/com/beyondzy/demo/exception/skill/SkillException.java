package com.beyondzy.demo.exception.skill;

/** 技能管理失败。status 是要返回给调用方的 HTTP 状态。 */
public class SkillException extends RuntimeException {
    private final int status;

    public SkillException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
