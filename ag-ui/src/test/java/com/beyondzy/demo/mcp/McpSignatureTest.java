package com.beyondzy.demo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class McpSignatureTest {

    @Test
    void signsTimestampNewlineAndPhone() {
        String signed = McpSignature.sign("test-secret", "1700000000000", "13800000000");
        assertEquals("08b3a463695ce6aee32271c941743856f4abd924d353638a4ae309537e63ded9", signed);
        assertNotEquals(signed, McpSignature.sign("test-secret", "1700000000000", "13800000001"));
    }
}
