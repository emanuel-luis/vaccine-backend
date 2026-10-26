package io.github.emanuelluis.vaccine.campaigns;

import io.github.emanuelluis.vaccine.security.RegionScope;
import io.github.emanuelluis.vaccine.web.UnprocessableException;

/** Value rules of a campaign body (422 with a {@code code} and the offending {@code field}). */
final class CampaignRules {

    static final int MAX_NAME = 120;

    private CampaignRules() {}

    static void validate(CampaignRequest r) {
        String name = r.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new UnprocessableException("invalid_name", "name", "name must have 1 to 120 characters");
        }
        if (!r.vaccineCode().matches("[A-Z0-9_]{2,20}")) {
            throw new UnprocessableException("invalid_vaccine_code", "vaccineCode", "vaccineCode is malformed");
        }
        if (r.doseLabel() != null && !r.doseLabel().matches("[A-Za-z0-9_]{1,10}")) {
            throw new UnprocessableException("invalid_dose_label", "doseLabel", "doseLabel is malformed");
        }
        if (RegionScope.parse(r.regionScope()).isEmpty()) {
            throw new UnprocessableException(
                    "invalid_region_scope",
                    "regionScope",
                    "regionScope must be BR, UF:<UF> or MUN:<7-digit IBGE code>");
        }
        if (r.endsOn().isBefore(r.startsOn())) {
            throw new UnprocessableException("ends_before_starts", "endsOn", "endsOn must not be before startsOn");
        }
        if (r.targetDoses() <= 0) {
            throw new UnprocessableException("invalid_target_doses", "targetDoses", "targetDoses must be positive");
        }
    }
}
