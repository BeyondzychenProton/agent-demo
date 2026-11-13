package com.beyondzy.demo.contract;

/** 已认证用户主体；userId 用于会话消息命名空间，role 用于管理权限判定。 */
public record AuthPrincipal(long userId, long authSessionId, String phone, String name, String role) {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    /** 是否管理员。role 为空按普通用户处理（fail-closed）。 */
    public boolean admin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }
}
