package com.beyondzy.demo.auth.support;

import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 在 SecurityContext 用户下执行阻塞业务，并写入 UserContext。 */
public final class AuthRequests {
    private AuthRequests() {}

    public static <T> Mono<T> call(Supplier<T> action) {
        return CurrentUser.requireUserId()
                .flatMap(userId -> Mono.fromCallable(() -> UserContext.call(String.valueOf(userId), action))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(SessionNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex));
    }

    public static <T> Flux<T> flux(Supplier<Flux<T>> action) {
        return CurrentUser.requireUserId()
                .flatMapMany(userId -> Flux.defer(() -> UserContext.call(String.valueOf(userId), action))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(SessionNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex));
    }
}
