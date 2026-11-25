package com.beyondzy.demo.web.mcp;

import com.beyondzy.demo.exception.mcp.McpManagementException;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.service.mcp.McpManagementService;
import com.beyondzy.demo.service.mcp.McpManagementService.Actor;
import com.beyondzy.demo.service.user.UserService;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/mcp/services")
public class McpAdminController {
    private final McpManagementService management;
    private final UserService users;
    public McpAdminController(McpManagementService management, UserService users) {
        this.management=management; this.users=users;
    }
    private Mono<Map<String,Object>> admin(Function<Actor,Map<String,Object>> action) {
        return CurrentUser.require().flatMap(principal->Mono.fromCallable(()->{
            var user=users.findById(principal.userId());
            if(user==null || !user.isAdministrator())
                throw new McpManagementException(403,"ADMIN_REQUIRED","需要平台管理员权限");
            return action.apply(new Actor(user.getId(),user.getName()));
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping({"","/"})
    public Mono<Map<String,Object>> list(@RequestParam(required=false) String q,
            @RequestParam(defaultValue="all") String state,@RequestParam(required=false) String transport,
            @RequestParam(required=false) String health,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return admin(a->management.list(q,state,transport,health,page,size));
    }
    @GetMapping("/{id}")
    public Mono<Map<String,Object>> detail(@PathVariable long id) { return admin(a->management.detail(id)); }
    @PutMapping("/{id}/metadata")
    public Mono<Map<String,Object>> metadata(@PathVariable long id,@RequestBody Map<String,Object> body) {
        return admin(a->{
            fields(body,Set.of("displayName","description","ownerName","expectedVersion"));
            return management.metadata(id,text(body,"displayName"),text(body,"description"),
                    text(body,"ownerName"),version(body),a);
        });
    }
    @PostMapping("/{id}/status")
    public Mono<Map<String,Object>> status(@PathVariable long id,@RequestBody Map<String,Object> body) {
        return admin(a->{fields(body,Set.of("enabled","expectedVersion")); return management.status(id,enabled(body),version(body),a);});
    }
    @GetMapping("/{id}/tools")
    public Mono<Map<String,Object>> tools(@PathVariable long id,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="all") String state,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return admin(a->management.toolPage(id,q,state,page,size));
    }
    @GetMapping("/{id}/tools/{toolId}")
    public Mono<Map<String,Object>> tool(@PathVariable long id,@PathVariable long toolId) {
        return admin(a->management.toolDetail(id,toolId));
    }
    @PostMapping("/{id}/tools/{toolId}/status")
    public Mono<Map<String,Object>> toolStatus(@PathVariable long id,@PathVariable long toolId,@RequestBody Map<String,Object> body) {
        return admin(a->{fields(body,Set.of("enabled","expectedVersion"));
            return management.toolStatus(id,toolId,enabled(body),version(body),a);});
    }
    @PostMapping("/{id}/verify")
    public Mono<Map<String,Object>> verify(@PathVariable long id) { return admin(a->management.verify(id,a)); }
    @GetMapping("/{id}/operations")
    public Mono<Map<String,Object>> history(@PathVariable long id,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) { return admin(a->management.history(id,page,size)); }
    private static void fields(Map<String,Object> body,Set<String> allowed) {
        if(!allowed.containsAll(body.keySet())) invalid();
    }
    private static String text(Map<String,Object> body,String name) {
        Object value=body.get(name);
        if(value!=null && !(value instanceof String)) invalid();
        return (String)value;
    }
    private static boolean enabled(Map<String,Object> body) {
        if(!(body.get("enabled") instanceof Boolean)) invalid();
        return (Boolean)body.get("enabled");
    }
    private static long version(Map<String,Object> body) {
        Object v=body.get("expectedVersion");
        if(!(v instanceof Integer || v instanceof Long) || ((Number)v).longValue()<0) invalid();
        return ((Number)v).longValue();
    }
    private static void invalid() { throw new McpManagementException(400,"INVALID_REQUEST","请求字段或类型无效"); }
}
