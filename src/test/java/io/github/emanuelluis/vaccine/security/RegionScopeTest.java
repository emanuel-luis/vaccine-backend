package io.github.emanuelluis.vaccine.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.RegionScope.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegionScopeTest {

    @Test
    void parsesTheThreeLevels() {
        assertThat(RegionScope.parse("BR")).contains(RegionScope.NATIONAL);
        assertThat(RegionScope.parse("UF:BA")).contains(new RegionScope(Level.UF, "BA", null));
        // the state of a municipality comes from the first two digits of the IBGE code
        assertThat(RegionScope.parse("MUN:2927408")).contains(new RegionScope(Level.MUN, "BA", 2927408));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "br", "UF:ba", "UF:XX", "UF:BAH", "MUN:123", "MUN:9927408", "MUN:29274080", "XX"})
    void anythingElseIsInvalid(String claim) {
        assertThat(RegionScope.parse(claim)).isEmpty();
    }

    @Test
    void aNonStringClaimIsInvalid() {
        assertThat(RegionScope.parse(42)).isEmpty();
    }

    @Test
    void labelsArePortuguese() {
        assertThat(RegionScope.NATIONAL.label()).isEqualTo("Brasil");
        assertThat(RegionScope.parse("UF:BA").orElseThrow().label()).isEqualTo("UF: BA");
        assertThat(RegionScope.parse("MUN:2927408").orElseThrow().label()).isEqualTo("Município: 2927408");
    }

    @Test
    void containsFollowsTheHierarchy() {
        var br = RegionScope.NATIONAL;
        var ba = RegionScope.parse("UF:BA").orElseThrow();
        var salvador = RegionScope.parse("MUN:2927408").orElseThrow();

        assertThat(br.contains(RegionFilter.parse("MUN:2111300"))).isTrue();
        assertThat(ba.contains(RegionFilter.BR)).isFalse();
        assertThat(ba.contains(RegionFilter.parse("UF:BA"))).isTrue();
        assertThat(ba.contains(RegionFilter.parse("UF:SP"))).isFalse();
        assertThat(ba.contains(RegionFilter.parse("MUN:2927408"))).isTrue();
        assertThat(ba.contains(RegionFilter.parse("MUN:3550308"))).isFalse();
        assertThat(salvador.contains(RegionFilter.parse("MUN:2927408"))).isTrue();
        assertThat(salvador.contains(RegionFilter.parse("MUN:2910800"))).isFalse();
        assertThat(salvador.contains(RegionFilter.parse("UF:BA"))).isFalse();
        assertThat(salvador.contains(RegionFilter.BR)).isFalse();
    }

    @Test
    void filterSelectsExactlyTheScope() {
        assertThat(RegionScope.NATIONAL.filter()).isEqualTo(RegionFilter.BR);
        assertThat(RegionScope.parse("UF:BA").orElseThrow().filter()).isEqualTo(new RegionFilter("BA", null));
        assertThat(RegionScope.parse("MUN:2927408").orElseThrow().filter()).isEqualTo(new RegionFilter(null, 2927408));
    }
}
