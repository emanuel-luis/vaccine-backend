package io.github.emanuelluis.vaccine;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Test principals: the claims Keycloak puts in access tokens ({@code roles}, {@code region_scope}, {@code azp}). */
public final class Auth {

    private Auth() {}

    /** MockMvc principal with the given roles; authorities mirror the production converter (ROLE_ prefix). */
    public static JwtRequestPostProcessor as(String azp, String scope, String... roles) {
        return jwt().jwt(j -> {
                    j.claim("roles", List.of(roles)).claim("azp", azp).claim("preferred_username", azp);
                    if (scope != null) {
                        j.claim("region_scope", scope);
                    }
                })
                .authorities(Arrays.stream(roles)
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    /** Like the vaccine-smoke client: gestor + data-admin, national scope. */
    public static JwtRequestPostProcessor reader() {
        return as("vaccine-smoke", "BR", "gestor", "data-admin");
    }

    /** Like the vaccine-simulator client. */
    public static JwtRequestPostProcessor ingest() {
        return as("vaccine-simulator", null, "dose-ingest");
    }

    /**
     * Decoder for RANDOM_PORT tests: the bearer value picks the principal ({@code reader} or
     * {@code ingest}); no signature is checked. Expires one hour after {@code issuedAt}.
     */
    public static JwtDecoder decoder(Instant issuedAt) {
        return token -> {
            boolean ingest = token.equals("ingest");
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(token)
                    .claim("azp", ingest ? "vaccine-simulator" : "vaccine-smoke")
                    .claim("roles", ingest ? List.of("dose-ingest") : List.of("gestor", "data-admin"))
                    .claim("region_scope", "BR")
                    .issuedAt(issuedAt)
                    .expiresAt(issuedAt.plusSeconds(3600))
                    .build();
        };
    }
}
