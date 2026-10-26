package io.github.emanuelluis.vaccine.campaigns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.emanuelluis.vaccine.web.UnprocessableException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CampaignRulesTest {

    private static CampaignRequest request(String name, String region, String from, String to, int target) {
        return new CampaignRequest(
                "%s".formatted(name), "PENTA", "D3", region, LocalDate.parse(from), LocalDate.parse(to), target);
    }

    private static void rejects(CampaignRequest r, String code, String field) {
        assertThatThrownBy(() -> CampaignRules.validate(r)).isInstanceOfSatisfying(UnprocessableException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test
    void acceptsAValidCampaign() {
        assertThatCode(() -> CampaignRules.validate(request("Campanha", "UF:BA", "2026-09-01", "2026-09-01", 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> CampaignRules.validate(request("x".repeat(120), "BR", "2026-09-01", "2026-09-30", 5)))
                .doesNotThrowAnyException();
        assertThatCode(() -> CampaignRules.validate(request("M", "MUN:2927408", "2026-09-01", "2026-09-30", 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEachBrokenRuleWithItsCode() {
        rejects(request("  ", "BR", "2026-09-01", "2026-09-30", 5), "invalid_name", "name");
        rejects(request("x".repeat(121), "BR", "2026-09-01", "2026-09-30", 5), "invalid_name", "name");
        rejects(request("A", "SP", "2026-09-01", "2026-09-30", 5), "invalid_region_scope", "regionScope");
        rejects(request("A", "UF:ZZ", "2026-09-01", "2026-09-30", 5), "invalid_region_scope", "regionScope");
        rejects(request("A", "MUN:123", "2026-09-01", "2026-09-30", 5), "invalid_region_scope", "regionScope");
        rejects(request("A", "BR", "2026-09-30", "2026-09-01", 5), "ends_before_starts", "endsOn");
        rejects(request("A", "BR", "2026-09-01", "2026-09-30", 0), "invalid_target_doses", "targetDoses");
        rejects(request("A", "BR", "2026-09-01", "2026-09-30", -3), "invalid_target_doses", "targetDoses");
        rejects(
                new CampaignRequest(
                        "A", "penta", null, "BR", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), 1),
                "invalid_vaccine_code",
                "vaccineCode");
        rejects(
                new CampaignRequest(
                        "A", "PENTA", "d 3", "BR", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), 1),
                "invalid_dose_label",
                "doseLabel");
    }
}
