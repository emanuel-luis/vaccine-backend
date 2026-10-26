package io.github.emanuelluis.vaccine.security;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.RegionScope.Level;
import io.github.emanuelluis.vaccine.web.ForbiddenException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Intersects what a request asks for with the caller's {@link RegionScope}. The returned
 * {@link RegionFilter} is the only region input repositories accept.
 */
public final class ScopeGuard {

    static final String MISSING = "missing_region_scope";
    static final String OUT_OF_SCOPE = "region_out_of_scope";

    private ScopeGuard() {}

    /** Scope from the token, or 403 {@code missing_region_scope} when the claim is absent or invalid. */
    public static RegionScope scope(Jwt jwt) {
        return RegionScope.parse(jwt.getClaim(RegionScope.CLAIM))
                .orElseThrow(() -> new ForbiddenException(MISSING, "the token carries no valid region_scope"));
    }

    /**
     * Rows restricted to the scope. No {@code region} means the whole scope; an explicit one must
     * lie inside it (403 {@code region_out_of_scope} otherwise).
     */
    public static RegionFilter filter(Jwt jwt, String region) {
        RegionScope scope = scope(jwt);
        if (region == null || region.isBlank()) {
            return scope.filter();
        }
        RegionFilter requested = RegionFilter.parse(region);
        if (!scope.contains(requested)) {
            throw outOfScope("region " + requested.label() + " is outside your scope");
        }
        return requested;
    }

    /**
     * State-grain data (S>=UF): municipality callers get 403; state callers are limited to their
     * state; national callers may pick any state or none.
     */
    public static RegionFilter stateFilter(Jwt jwt, String state) {
        RegionScope scope = scope(jwt);
        if (scope.level() == Level.MUN) {
            throw outOfScope("this data is available at state level or wider");
        }
        if (scope.level() == Level.UF) {
            if (state != null && !state.equals(scope.state())) {
                throw outOfScope("state " + state + " is outside your scope");
            }
            return scope.filter();
        }
        return new RegionFilter(state, null);
    }

    /**
     * Areas of one state (its municipalities): the state must lie inside the scope, or hold the
     * caller's municipality, whose only area is then returned.
     */
    public static RegionFilter stateAreas(Jwt jwt, String state) {
        RegionScope scope = scope(jwt);
        if (scope.level() != Level.BR && !state.equals(scope.state())) {
            throw outOfScope("state " + state + " is outside your scope");
        }
        return new RegionFilter(state, scope.municipalityCode());
    }

    /** 403 unless a row of this state and municipality is inside the caller's scope. */
    public static void requireCovers(Jwt jwt, String state, int municipalityCode) {
        if (!scope(jwt).filter().covers(state, municipalityCode)) {
            throw outOfScope("this unit is outside your scope");
        }
    }

    private static ForbiddenException outOfScope(String message) {
        return new ForbiddenException(OUT_OF_SCOPE, message);
    }
}
