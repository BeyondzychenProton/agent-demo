package com.beyondzy.demo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 确保默认配置可直接启动，不依赖外部环境变量注入。 */
class ApplicationPropertiesTest {
    @Test
    void chatApiKeyIsConfiguredLiterally() throws Exception {
        Path file = Path.of("src/main/resources/application.properties");
        String text = Files.readString(file);
        assertTrue(
                text.lines()
                        .anyMatch(
                                line ->
                                        line.startsWith("chat.api-key=")
                                                && !line.contains("${")
                                                && line.length() > "chat.api-key=".length()),
                "chat.api-key 应写明文字符串，方便同事直接运行");
        assertFalse(text.contains("${CHAT_"), "配置不应再依赖 CHAT_* 环境变量");
        assertFalse(text.contains("${MYSQL_"), "配置不应再依赖 MYSQL_* 环境变量");
    }
}
