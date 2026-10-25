package io.github.emanuelluis.vaccine.security;

import io.github.emanuelluis.vaccine.web.ApiItem;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller as the token describes it. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /** Region scope for the UI; {@code state} and {@code municipalityCode} are null when they do not apply. */
    public record ScopeView(String level, String state, Integer municipalityCode, String label) {}

    /** Data of {@code /me}; {@code scope} is null when the token has no valid region_scope. */
    public record Me(String username, String name, List<String> roles, ScopeView scope) {}

    @GetMapping
    public ApiItem<Me> me(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");
        List<String> roles = jwt.getClaimAsStringList("roles");
        ScopeView scope = RegionScope.parse(jwt.getClaim(RegionScope.CLAIM))
                .map(s -> new ScopeView(s.level().name(), s.state(), s.municipalityCode(), s.label()))
                .orElse(null);
        var me = new Me(
                username != null ? username : jwt.getSubject(),
                name != null ? name : username,
                roles == null ? List.of() : roles.stream().sorted().toList(),
                scope);
        return ApiItem.of("jwt", false, ApiResponse.LAYER_BATCH, null, me);
    }
}
