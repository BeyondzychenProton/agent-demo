package com.beyondzy.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>本 demo 验证大纲 5.4 的最小后端闭环：提交任务 → committed 事实落 SessionLog →
 * 快照现算视图 → SSE 按 cursor/Last-Event-ID 续增量 → 重启后从本地日志完整恢复。
 *
 * <p>运行时的模型与会话装配见 {@code com.beyondzy.demo.config.chat.ChatConfiguration}。
 * Mapper 扫描见 {@code com.beyondzy.demo.config.mybatis.MybatisPlusConfiguration}。
 */
@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
