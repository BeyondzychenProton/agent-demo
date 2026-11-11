package com.beyondzy.demo.mcp;

/** 按已登录用户 id 取手机号。找不到时返回 null，不能改用模型参数里的号码。 */
@FunctionalInterface
public interface McpPhoneLookup {
    String phoneOf(String userId);
}
