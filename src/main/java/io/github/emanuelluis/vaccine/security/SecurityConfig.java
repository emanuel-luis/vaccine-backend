package io.github.emanuelluis.vaccine.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless bearer-token resource server. Authorization is URL based and mirrors the API table in
 * docs/WEB_PLATFORM_PLAN.md section 4; region scoping happens in {@link ScopeGuard}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String PROFISSIONAL = "profissional";
    public static final String GESTOR = "gestor";
    public static final String DATA_ADMIN = "data-admin";
    public static final String DOSE_INGEST = "dose-ingest";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/doses")
                        .hasRole(DOSE_INGEST)
                        .requestMatchers("/api/v1/runs/**")
                        .hasRole(DATA_ADMIN)
                        .requestMatchers("/api/v1/**")
                        .hasAnyRole(PROFISSIONAL, GESTOR, DATA_ADMIN)
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(deniedHandler()))
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(deniedHandler())
                        .jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    /** Authorities from the top-level multivalued claim {@code roles}, as {@code ROLE_<role>}. */
    static JwtAuthenticationConverter jwtConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            problem(response, HttpStatus.UNAUTHORIZED, "unauthorized", "a valid bearer token is required");
        };
    }

    private static AccessDeniedHandler deniedHandler() {
        return (request, response, ex) ->
                problem(response, HttpStatus.FORBIDDEN, "forbidden", "your roles do not allow this request");
    }

    /** RFC 9457 body with a {@code code}; all values are fixed strings, so no JSON escaping is needed. */
    private static void problem(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                        .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }
}
