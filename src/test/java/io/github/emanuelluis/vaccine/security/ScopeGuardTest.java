package io.github.emanuelluis.vaccine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.web.ForbiddenException;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ScopeGuardTest {

    private static Jwt token(String scope) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("u");
        return (scope == null ? builder.claim("x", "y") : builder.claim("region_scope", scope)).build();
    }

    private static void denied(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ForbiddenException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void missingOrInvalidClaimIsForbidden() {
        denied(() -> ScopeGuard.filter(token(null), null), "missing_region_scope");
        denied(() -> ScopeGuard.filter(token("planet:earth"), null), "missing_region_scope");
        denied(() -> ScopeGuard.stateFilter(token(null), null), "missing_region_scope");
        denied(() -> ScopeGuard.requireCovers(token(null), "BA", 2927408), "missing_region_scope");
    }

    @Test
    void noRegionMeansTheWholeScope() {
        assertThat(ScopeGuard.filter(token("BR"), null)).isEqualTo(RegionFilter.BR);
        assertThat(ScopeGuard.filter(token("UF:BA"), " ")).isEqualTo(new RegionFilter("BA", null));
        assertThat(ScopeGuard.filter(token("MUN:2927408"), null)).isEqualTo(new RegionFilter(null, 2927408));
    }

    @Test
    void anExplicitRegionMustLieInsideTheScope() {
        assertThat(ScopeGuard.filter(token("BR"), "UF:SP")).isEqualTo(new RegionFilter("SP", null));
        assertThat(ScopeGuard.filter(token("UF:BA"), "MUN:2927408")).isEqualTo(new RegionFilter(null, 2927408));
        assertThat(ScopeGuard.filter(token("MUN:2927408"), "MUN:2927408")).isEqualTo(new RegionFilter(null, 2927408));

        denied(() -> ScopeGuard.filter(token("UF:BA"), "BR"), "region_out_of_scope");
        denied(() -> ScopeGuard.filter(token("UF:BA"), "UF:SP"), "region_out_of_scope");
        denied(() -> ScopeGuard.filter(token("UF:BA"), "MUN:3550308"), "region_out_of_scope");
        denied(() -> ScopeGuard.filter(token("MUN:2927408"), "UF:BA"), "region_out_of_scope");
        denied(() -> ScopeGuard.filter(token("MUN:2927408"), "MUN:2910800"), "region_out_of_scope");
    }

    @Test
    void aMalformedRegionIsABadRequestNotAScopeError() {
        assertThatThrownBy(() -> ScopeGuard.filter(token("BR"), "UF:ba")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void stateGrainDataIsClosedToMunicipalityCallers() {
        denied(() -> ScopeGuard.stateFilter(token("MUN:2927408"), null), "region_out_of_scope");
        denied(() -> ScopeGuard.stateFilter(token("MUN:2927408"), "BA"), "region_out_of_scope");
    }

    @Test
    void stateCallersAreLimitedToTheirState() {
        assertThat(ScopeGuard.stateFilter(token("UF:BA"), null)).isEqualTo(new RegionFilter("BA", null));
        assertThat(ScopeGuard.stateFilter(token("UF:BA"), "BA")).isEqualTo(new RegionFilter("BA", null));
        denied(() -> ScopeGuard.stateFilter(token("UF:BA"), "SP"), "region_out_of_scope");
    }

    @Test
    void nationalCallersPickAnyStateOrNone() {
        assertThat(ScopeGuard.stateFilter(token("BR"), null)).isEqualTo(RegionFilter.BR);
        assertThat(ScopeGuard.stateFilter(token("BR"), "SP")).isEqualTo(new RegionFilter("SP", null));
    }

    @Test
    void aUnitMustBeInsideTheScope() {
        ScopeGuard.requireCovers(token("BR"), "MA", 2111300);
        ScopeGuard.requireCovers(token("UF:BA"), "BA", 2927408);
        ScopeGuard.requireCovers(token("MUN:2927408"), "BA", 2927408);
        denied(() -> ScopeGuard.requireCovers(token("UF:BA"), "MA", 2111300), "region_out_of_scope");
        denied(() -> ScopeGuard.requireCovers(token("MUN:2927408"), "BA", 2910800), "region_out_of_scope");
    }
}
