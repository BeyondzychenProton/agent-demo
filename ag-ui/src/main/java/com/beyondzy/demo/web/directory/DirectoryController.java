package com.beyondzy.demo.web.directory;

import com.beyondzy.demo.contract.directory.DirectoryPush;
import com.beyondzy.demo.enums.mcp.McpProvider;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.service.directory.DirectoryService;
import com.beyondzy.demo.service.user.UserService;
import com.beyondzy.demo.service.directory.DirectoryService.PushStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 满天星推送和管理员通讯录查询入口。 */
@Tag(name = "通讯录", description = "满天星推送和管理员通讯录查询")
@RestController
public class DirectoryController {
    public static final String PUSH_PATH =
            "/api/integrations/" + McpProvider.Code.MANTIANXING + "/v1/directory/changes";

    private final DirectoryService directory;
    private final UserService users;
    private final byte[] syncToken;

    public DirectoryController(DirectoryService directory, UserService users,
                               @Value("${app.directory.sync-token:${MTX_DIRECTORY_SYNC_TOKEN:}}") String syncToken) {
        this.directory = directory;
        this.users = users;
        this.syncToken = syncToken.isBlank() ? new byte[0] : syncToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = PUSH_PATH, consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "接收通讯录变更")
    public Mono<Void> push(@RequestHeader(name = "X-MTX-Sync-Token", required = false) String token,
                           @RequestBody Mono<DirectoryPush> body) {
        if (syncToken.length == 0) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Directory sync disabled"));
        }
        if (token == null || !MessageDigest.isEqual(syncToken, token.getBytes(StandardCharsets.UTF_8))) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid sync token"));
        }
        return body.switchIfEmpty(Mono.error(new ServerWebInputException("Missing request body")))
                .onErrorResume(ServerWebInputException.class, error ->
                        Mono.fromRunnable(() -> recordFailure(null, PushStatus.REJECTED, error))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(error)))
                .flatMap(push -> Mono.<Void>fromRunnable(() -> {
                    try {
                        directory.apply(push);
                    } catch (RuntimeException error) {
                        recordFailure(push, PushStatus.FAILED, error);
                        throw error;
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 业务事务回滚后尝试单独留痕，保留原始异常供接口返回。 */
    private void recordFailure(DirectoryPush push, PushStatus status, RuntimeException error) {
        try {
            directory.recordPush(push, status);
        } catch (RuntimeException historyError) {
            error.addSuppressed(historyError);
        }
    }

    @GetMapping("/api/admin/directory")
    @Operation(summary = "查询通讯录")
    public Mono<DirectoryService.Directory> read() {
        return CurrentUser.require().flatMap(principal -> Mono.fromCallable(() -> {
            var user = users.findById(principal.userId());
            if (user == null || !user.isAdministrator()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
            }
            return directory.read();
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}
