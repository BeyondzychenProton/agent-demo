package com.beyondzy.demo.config;

import com.beyondzy.demo.auth.security.SessionTokenAuthenticationConverter;
import com.beyondzy.demo.auth.security.SessionTokenAuthenticationManager;
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
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/login")));
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
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers("/api/**", "/agui/**").authenticated()
                        .anyExchange().permitAll())
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint((exchange, ex) -> writeUnauthorized(exchange))
                        .accessDeniedHandler((exchange, ex) -> writeUnauthorized(exchange)))
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

    private static Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"未登录或会话已失效\"}".getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
