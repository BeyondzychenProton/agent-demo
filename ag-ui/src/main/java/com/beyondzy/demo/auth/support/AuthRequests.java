package com.beyondzy.demo.auth.support;

import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 在 SecurityContext 用户下执行阻塞业务，并写入 UserContext 与提交期会话凭证。 */
public final class AuthRequests {
    private AuthRequests() {}

    public static <T> Mono<T> call(Supplier<T> action) {
        return CurrentUser.requireUserId()
                .flatMap(userId -> rawSessionToken()
                        .defaultIfEmpty("")
                        .flatMap(rawToken -> Mono.fromCallable(() -> UserContext.call(
                                        String.valueOf(userId),
                                        () -> SubmitCredentialCapture.call(blankToNull(rawToken), action)))
                                .subscribeOn(Schedulers.boundedElastic())))
                .onErrorMap(SessionNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex));
    }

    public static <T> Flux<T> flux(Supplier<Flux<T>> action) {
        return CurrentUser.requireUserId()
                .flatMapMany(userId -> rawSessionToken()
                        .defaultIfEmpty("")
                        .flatMapMany(rawToken -> Flux.defer(() -> UserContext.call(
                                        String.valueOf(userId),
                                        () -> SubmitCredentialCapture.call(blankToNull(rawToken), action)))
                                .subscribeOn(Schedulers.boundedElastic())))
                .onErrorMap(SessionNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex));
    }

    private static Mono<String> rawSessionToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.getCredentials() != null)
                .map(auth -> String.valueOf(auth.getCredentials()))
                .filter(token -> !token.isBlank());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
