package com.beyondzy.demo.service.skill;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beyondzy.demo.exception.skill.SkillException;

/** 检查报告和结果快照的 JSON。字段可见，避免依赖 Lombok。 */
public final class SkillJson {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private SkillJson() {}

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new SkillException(500, "CHECK_BLOCKED", "检查报告无法保存");
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new SkillException(500, "CHECK_BLOCKED", "检查报告无法读取");
        }
    }
}
