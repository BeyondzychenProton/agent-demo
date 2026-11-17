package com.beyondzy.demo.handler.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminAccess {
    private final String phone;

    public AdminAccess(@Value("${app.admin.phone:15738515168}") String phone) {
        this.phone = phone.trim();
    }

    public boolean isAdmin(String candidate) {
        return !phone.isBlank() && phone.equals(candidate);
    }
}
