package com.beyondzy.demo.handler.mcp;
import com.beyondzy.demo.exception.mcp.McpManagementException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(basePackages = "com.beyondzy.demo.web.mcp")
public class McpManagementExceptionHandler {
    @ExceptionHandler(McpManagementException.class)
    public ResponseEntity<?> handle(McpManagementException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage(DataAccessException e) {
        return ResponseEntity.status(503).body(Map.of("code","MCP_STORAGE_UNAVAILABLE",
                "message","MCP 管理存储不可用，请检查数据库连接及 V14 迁移"));
    }
}
