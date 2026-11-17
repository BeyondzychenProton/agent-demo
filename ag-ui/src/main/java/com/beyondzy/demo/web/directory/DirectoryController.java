package com.beyondzy.demo.web.directory;

import com.beyondzy.demo.handler.admin.AdminAccess;
import com.beyondzy.demo.handler.admin.CurrentUser;
import com.beyondzy.demo.web.directory.DirectoryStore.PushStatus;
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

@RestController
public class DirectoryController {
    public static final String PUSH_PATH = "/api/integrations/mantianxing/v1/directory/changes";

    private final DirectoryStore store;
    private final AdminAccess admin;
    private final byte[] syncToken;

    public DirectoryController(DirectoryStore store, AdminAccess admin,
                               @Value("${app.directory.sync-token:${MTX_DIRECTORY_SYNC_TOKEN:}}") String syncToken) {
        this.store = store;
        this.admin = admin;
        this.syncToken = syncToken.isBlank() ? new byte[0] : syncToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = PUSH_PATH, consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
                        store.apply(push);
                    } catch (RuntimeException error) {
                        recordFailure(push, PushStatus.FAILED, error);
                        throw error;
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private void recordFailure(DirectoryPush push, PushStatus status, RuntimeException error) {
        try {
            store.recordPush(push, status);
        } catch (RuntimeException historyError) {
            error.addSuppressed(historyError);
        }
    }

    @GetMapping("/api/admin/directory")
    public Mono<DirectoryStore.Directory> read() {
        return CurrentUser.require().flatMap(principal -> {
            if (!admin.isAdmin(principal.phone())) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"));
            }
            return Mono.fromCallable(store::read).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
