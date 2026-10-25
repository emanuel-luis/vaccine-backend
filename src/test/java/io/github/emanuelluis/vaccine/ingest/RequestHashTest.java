package io.github.emanuelluis.vaccine.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DoseRequest request(String sex, String lot, String at) {
        return new DoseRequest(
                PERSON, LocalDate.of(2026, 6, 1), sex, "0000264", "PENTA", "D1", lot, OffsetDateTime.parse(at));
    }

    @Test
    void isStableSha256Hex() {
        String hash = RequestHash.sha256(request("F", null, "2026-10-25T13:00:00Z"));
        assertThat(hash)
                .matches("[0-9a-f]{64}")
                .isEqualTo(RequestHash.sha256(request("F", null, "2026-10-25T13:00:00Z")));
    }

    @Test
    void sameInstantInAnotherOffsetHashesTheSame() {
        assertThat(RequestHash.sha256(request("F", null, "2026-10-25T10:00:00-03:00")))
                .isEqualTo(RequestHash.sha256(request("F", null, "2026-10-25T13:00:00Z")));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = RequestHash.sha256(request("F", null, "2026-10-25T13:00:00Z"));
        assertThat(RequestHash.sha256(request("M", null, "2026-10-25T13:00:00Z")))
                .isNotEqualTo(base);
        assertThat(RequestHash.sha256(request("F", "LOT-1", "2026-10-25T13:00:00Z")))
                .isNotEqualTo(base);
        assertThat(RequestHash.sha256(request("F", null, "2026-10-25T13:00:01Z")))
                .isNotEqualTo(base);
    }
}
