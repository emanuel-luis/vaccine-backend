package io.github.emanuelluis.vaccine.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import org.junit.jupiter.api.Test;

class SuppressionTest {

    @Test
    void smallCountsAreHiddenAtMunicipalityOrUnitGranularity() {
        for (long n = 1; n <= 4; n++) {
            assertThat(Suppression.applies(n, true)).as("count %d", n).isTrue();
        }
    }

    @Test
    void zeroAndCountsFromKUpAreNeverHidden() {
        assertThat(Suppression.applies(0, true)).isFalse();
        assertThat(Suppression.applies(Suppression.K, true)).isFalse();
        assertThat(Suppression.applies(1_000_000, true)).isFalse();
    }

    @Test
    void nationalAndStateCellsAreNeverHidden() {
        for (long n = 0; n <= 6; n++) {
            assertThat(Suppression.applies(n, false)).as("count %d", n).isFalse();
        }
    }

    @Test
    void onlyAMunicipalityRegionIsFine() {
        assertThat(Suppression.fine(RegionFilter.BR)).isFalse();
        assertThat(Suppression.fine(new RegionFilter("BA", null))).isFalse();
        assertThat(Suppression.fine(new RegionFilter(null, 2927408))).isTrue();
        assertThat(Suppression.fine(RegionFilter.parse("MUN:2927408"))).isTrue();
    }
}
