package com.beyondzy.demo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 防止把真实模型密钥提交进仓库默认配置。 */
class ApplicationPropertiesTest {
    @Test
    void chatApiKeyUsesEnvironmentPlaceholder() throws Exception {
        Path file = Path.of("src/main/resources/application.properties");
        String text = Files.readString(file);
        assertTrue(text.contains("chat.api-key=${CHAT_API_KEY:}"),
                "chat.api-key 必须通过环境变量注入，不能写死密钥");
        assertFalse(text.lines().anyMatch(line -> line.startsWith("chat.api-key=")
                        && !line.contains("${CHAT_API_KEY")
                        && line.length() > "chat.api-key=".length()),
                "chat.api-key 不能是明文密钥");
    }
}
