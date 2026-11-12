package com.beyondzy.demo.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.service.user.UserService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrentUserToolTest {
    @Test
    void usesRuntimeIdentityAndNeverReturnsPassword() {
        UserService users = mock(UserService.class);
        UserBean user = new UserBean();
        user.setId(7L); user.setName("测试用户"); user.setPhone("13800000000"); user.setPasswordHash("secret-hash");
        when(users.findById(7L)).thenReturn(user);
        CurrentUserTool tool = new CurrentUserTool(users);
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .input(Map.of("userId", 8))
                .runtimeContext(RuntimeContext.builder().userId("7").sessionId("s").build()).build()).block();
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(text.contains("测试用户"));
        assertTrue(text.contains("13800000000"));
        assertFalse(text.contains("secret-hash"));
        verify(users).findById(7L);
        verify(users, never()).findById(8L);
    }

    @Test
    void rejectsMissingInvalidAndUnknownUser() {
        UserService users = mock(UserService.class);
        CurrentUserTool tool = new CurrentUserTool(users);
        assertEquals(ToolResultState.ERROR, tool.callAsync(null).block().getState());
        assertEquals(ToolResultState.ERROR, tool.callAsync(ToolCallParam.builder()
                .runtimeContext(RuntimeContext.builder().userId("invalid").sessionId("s").build()).build()).block().getState());
        assertEquals(ToolResultState.ERROR, tool.callAsync(ToolCallParam.builder()
                .runtimeContext(RuntimeContext.builder().userId("42").sessionId("s").build()).build()).block().getState());
        verify(users).findById(42L);
        verifyNoMoreInteractions(users);
    }
}
