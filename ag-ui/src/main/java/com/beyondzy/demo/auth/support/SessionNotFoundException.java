package com.beyondzy.demo.auth.support;

/** 会话不存在或不属于当前用户。 */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String message) {
        super(message);
    }
}
