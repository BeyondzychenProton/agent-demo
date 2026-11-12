package com.beyondzy.demo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserContextTest {
    @Test
    void nestsAndRestoresPrevious() {
        assertTrue(UserContext.current().isEmpty());
        UserContext.run("1", () -> {
            assertEquals("1", UserContext.require());
            UserContext.run("2", () -> assertEquals("2", UserContext.require()));
            assertEquals("1", UserContext.require());
        });
        assertTrue(UserContext.current().isEmpty());
    }
}
