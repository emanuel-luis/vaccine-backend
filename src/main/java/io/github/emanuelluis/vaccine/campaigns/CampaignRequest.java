package io.github.emanuelluis.vaccine.campaigns;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST} and {@code PUT /api/v1/campaigns}; value rules live in {@link CampaignRules}. */
public record CampaignRequest(
        @NotNull String name,
        @NotNull String vaccineCode,
        @Nullable String doseLabel,
        @NotNull String regionScope,
        @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn,
        @NotNull Integer targetDoses) {}
