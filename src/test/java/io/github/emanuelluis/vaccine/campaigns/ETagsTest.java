package io.github.emanuelluis.vaccine.campaigns;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ETagsTest {

    @Test
    void formatsTheVersionAsAQuotedTag() {
        assertThat(ETags.format(7)).isEqualTo("\"7\"");
    }

    @Test
    void parsesOnlyOurStrongTags() {
        assertThat(ETags.parse("\"3\"")).isEqualTo(3);
        assertThat(ETags.parse(" \"12\" ")).isEqualTo(12);
        assertThat(ETags.parse(ETags.format(41))).isEqualTo(41);
    }

    @Test
    void anythingElseNeverMatches() {
        for (String value :
                new String[] {"3", "W/\"3\"", "*", "\"abc\"", "\"-1\"", "\"\"", "\"3\", \"4\"", "\"9999999999\""}) {
            assertThat(ETags.parse(value)).as(value).isEqualTo(-1);
        }
    }
}
