package com.beyondzy.demo.config.security;

import com.beyondzy.demo.handler.admin.SessionTokenAuthenticationConverter;
import com.beyondzy.demo.handler.admin.SessionTokenAuthenticationManager;
import com.beyondzy.demo.web.directory.DirectoryController;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Spring Security WebFlux：Cookie/Bearer 不透明会话。 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            SessionTokenAuthenticationManager authenticationManager,
            SessionTokenAuthenticationConverter authenticationConverter) {
        AuthenticationWebFilter authFilter = new AuthenticationWebFilter(authenticationManager);
        authFilter.setServerAuthenticationConverter(authenticationConverter);
        authFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        authFilter.setRequiresAuthenticationMatcher(new NegatedServerWebExchangeMatcher(
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/login", DirectoryController.PUSH_PATH)));
        authFilter.setAuthenticationSuccessHandler(renewingSuccessHandler(authenticationManager));
        authFilter.setAuthenticationFailureHandler((exchange, ex) ->
                writeUnauthorized(exchange.getExchange()));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/api/auth/login", DirectoryController.PUSH_PATH).permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/auth/password", "/api/auth/logout").authenticated()
                        .pathMatchers("/api/**", "/agui/**")
                        .hasAuthority(SessionTokenAuthenticationManager.APP_ACCESS)
                        .pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .hasAuthority(SessionTokenAuthenticationManager.APP_ACCESS)
                        .anyExchange().permitAll())
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint((exchange, ex) -> writeUnauthorized(exchange))
                        .accessDeniedHandler((exchange, ex) -> writePasswordChangeRequired(exchange)))
                .addFilterAt(authFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    private static ServerAuthenticationSuccessHandler renewingSuccessHandler(
            SessionTokenAuthenticationManager manager) {
        return (webFilterExchange, authentication) -> {
            ServerWebExchange exchange = webFilterExchange.getExchange();
            if (authentication.getDetails() instanceof SessionTokenAuthenticationManager.AuthOutcome outcome) {
                manager.renewCookieIfNeeded(exchange, outcome);
            }
            return webFilterExchange.getChain().filter(exchange);
        };
    }

    private static Mono<Void> writePasswordChangeRequired(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.FORBIDDEN, "{\"error\":\"PASSWORD_CHANGE_REQUIRED\"}");
    }

    private static Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        return writeError(exchange, HttpStatus.UNAUTHORIZED, "{\"error\":\"未登录或会话已失效\"}");
    }

    private static Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String errorJson) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = errorJson.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
