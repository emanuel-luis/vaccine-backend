package io.github.emanuelluis.vaccine.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegionFilterTest {

    @Test
    void parsesTheThreeLevels() {
        assertThat(RegionFilter.parse(null)).isEqualTo(RegionFilter.BR);
        assertThat(RegionFilter.parse("BR")).isEqualTo(RegionFilter.BR);
        assertThat(RegionFilter.parse("UF:BA")).isEqualTo(new RegionFilter("BA", null));
        assertThat(RegionFilter.parse("MUN:2927408")).isEqualTo(new RegionFilter(null, 2927408));
        assertThat(RegionFilter.parse("UF:BA").label()).isEqualTo("UF:BA");
        assertThat(RegionFilter.parse("MUN:2927408").label()).isEqualTo("MUN:2927408");
    }

    @ParameterizedTest
    @ValueSource(strings = {"br", "UF:ba", "UF:BAH", "MUN:123", "MUN:29274080", "XX:1", "UF:"})
    void rejectsAnythingElse(String region) {
        assertThatThrownBy(() -> RegionFilter.parse(region)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void coversMatchesTheScope() {
        assertThat(RegionFilter.BR.covers("MA", 1)).isTrue();
        assertThat(new RegionFilter("MA", null).covers("MA", 1)).isTrue();
        assertThat(new RegionFilter("MA", null).covers("SP", 1)).isFalse();
        assertThat(new RegionFilter(null, 7).covers("MA", 7)).isTrue();
        assertThat(new RegionFilter(null, 7).covers("MA", 8)).isFalse();
    }
}
