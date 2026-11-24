package com.beyondzy.demo.exception.skill;

import java.util.Map;

/** 技能管理失败。status 是要返回给调用方的 HTTP 状态。code 为空时响应只保留 error。 */
public class SkillException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public SkillException(int status, String message) {
        this(status, null, message, Map.of());
    }

    public SkillException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public SkillException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null || details.isEmpty() ? Map.of() : Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
