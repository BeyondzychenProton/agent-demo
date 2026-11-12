package com.beyondzy.demo.tools;

import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.user.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 只读取当前执行上下文对应的登录用户，不接受模型指定用户。 */
public final class CurrentUserTool extends ToolBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UserService users;

    public CurrentUserTool(UserService users) {
        super(ToolBase.builder()
                .name("get_current_user")
                .description("查询当前登录用户的账号信息：用户 ID、姓名和手机号。无需参数，只能查询本人。")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .readOnly(true).concurrencySafe(true));
        this.users = java.util.Objects.requireNonNull(users);
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow(getName()));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param == null ? null : param.getRuntimeContext();
        String id = context == null ? null : context.getUserId();
        if (id == null || !id.matches("[0-9]{1,19}")) {
            return Mono.just(ToolResultBlock.error("无法确认当前登录用户"));
        }
        return Mono.fromCallable(() -> {
            long userId;
            try { userId = Long.parseLong(id); }
            catch (NumberFormatException error) { return ToolResultBlock.error("无法确认当前登录用户"); }
            UserBean user = users.findById(userId);
            if (user == null) return ToolResultBlock.error("当前用户不存在或已停用");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", user.getId());
            result.put("name", user.getName());
            result.put("phone", user.getPhone());
            try { return ToolResultBlock.text(JSON.writeValueAsString(result)).withState(ToolResultState.SUCCESS); }
            catch (JsonProcessingException error) { return ToolResultBlock.error("无法生成用户信息"); }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
