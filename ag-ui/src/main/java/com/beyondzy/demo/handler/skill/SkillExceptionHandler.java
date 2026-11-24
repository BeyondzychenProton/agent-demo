package com.beyondzy.demo.handler.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把技能校验和冲突映射成 HTTP 状态。旧客户端仍只读 error。 */
@RestControllerAdvice
public class SkillExceptionHandler {
    @ExceptionHandler(SkillException.class)
    public ResponseEntity<Map<String, Object>> handle(SkillException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage() == null ? "" : error.getMessage());
        if (error.code() != null) body.put("code", error.code());
        if (error.details() != null && !error.details().isEmpty()) body.put("details", error.details());
        return ResponseEntity.status(error.status()).body(body);
    }
}
