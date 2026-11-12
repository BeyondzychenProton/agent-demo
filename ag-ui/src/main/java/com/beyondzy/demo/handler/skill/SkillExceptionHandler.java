package com.beyondzy.demo.handler.skill;

import com.beyondzy.demo.exception.skill.SkillException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把技能校验和冲突映射成 HTTP 状态。 */
@RestControllerAdvice
public class SkillExceptionHandler {
    @ExceptionHandler(SkillException.class)
    public ResponseEntity<Map<String, String>> handle(SkillException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }
}
