package com.beyondzy.demo.web.mcp;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.beyondzy.demo.contract.AuthPrincipal;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.exception.mcp.McpManagementException;
import com.beyondzy.demo.service.mcp.McpManagementService;
import com.beyondzy.demo.service.user.UserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class McpAdminControllerTest {
    @Test void missingLoginAndNonAdminCannotReachManagement() {
        var service=mock(McpManagementService.class);var users=mock(UserService.class);
        var controller=new McpAdminController(service,users);
        assertThrows(ResponseStatusException.class,()->controller.detail(1).block());
        var user=new UserBean();user.setId(1L);user.setIsAdmin(0);when(users.findById(1)).thenReturn(user);
        var e=assertThrows(McpManagementException.class,()->login(controller.detail(1)).block());
        assertEquals(403,e.status());verifyNoInteractions(service);
    }
    @Test void adminIdentityComesFromLoginAndUnknownFieldsAreRejected() {
        var service=mock(McpManagementService.class);var users=mock(UserService.class);
        var user=new UserBean();user.setId(1L);user.setName("当前管理员");user.setIsAdmin(1);when(users.findById(1)).thenReturn(user);
        var controller=new McpAdminController(service,users);
        var error=assertThrows(McpManagementException.class,()->login(controller.status(1,
                Map.of("enabled",true,"expectedVersion",0,"actorId",999))).block());
        assertEquals(400,error.status());verifyNoInteractions(service);
        when(service.status(eq(1L),eq(false),eq(0L),any())).thenReturn(Map.of("enabled",false));
        assertEquals(false,login(controller.status(1,Map.of("enabled",false,"expectedVersion",0))).block().get("enabled"));
        verify(service).status(1,false,0,new McpManagementService.Actor(1L,"当前管理员"));
    }
    private static <T> Mono<T> login(Mono<T> work) {
        var auth=new UsernamePasswordAuthenticationToken(new AuthPrincipal(1,1,"test","test"),null,java.util.List.of());
        return work.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }
}
