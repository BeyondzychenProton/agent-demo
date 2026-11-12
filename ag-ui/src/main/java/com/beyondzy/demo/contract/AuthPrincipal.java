package com.beyondzy.demo.contract;

/** 已认证用户主体；userId 用于会话消息命名空间。 */
public record AuthPrincipal(long userId, long authSessionId, String phone, String name) {}
