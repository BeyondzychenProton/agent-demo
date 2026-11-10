package com.beyondzy.demo.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 机器共享密钥：专用头与治理侧 Basic 机器凭证都能校验。 */
class IdentityConnectorKeySupportTest {

    @Test
    void acceptsDedicatedHeader() {
        assertTrue(IdentityConnectorKeySupport.matches(
                "shared-dev-key",
                "shared-dev-key",
                null));
    }

    @Test
    void acceptsGovernanceBasicClientAuthorization() {
        String basic = Base64.getEncoder()
                .encodeToString("demo-app-key:shared-dev-key".getBytes(StandardCharsets.UTF_8));
        assertTrue(IdentityConnectorKeySupport.matches(
                "shared-dev-key",
                null,
                "Basic " + basic));
    }

    @Test
    void rejectsWrongSecret() {
        String basic = Base64.getEncoder()
                .encodeToString("demo-app-key:other".getBytes(StandardCharsets.UTF_8));
        assertFalse(IdentityConnectorKeySupport.matches("shared-dev-key", "nope", null));
        assertFalse(IdentityConnectorKeySupport.matches("shared-dev-key", null, "Basic " + basic));
        assertFalse(IdentityConnectorKeySupport.matches("shared-dev-key", null, null));
        assertFalse(IdentityConnectorKeySupport.matches("", "shared-dev-key", null));
    }
}
