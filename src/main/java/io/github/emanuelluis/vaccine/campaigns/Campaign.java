package io.github.emanuelluis.vaccine.campaigns;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A vaccination campaign ({@code app.campaign}). {@code doseLabel} null means every dose of the
 * vaccine; {@code regionScope} uses the syntax of the token claim ({@code BR}, {@code UF:BA}, {@code MUN:2927408}).
 */
public record Campaign(
        UUID campaignId,
        String name,
        String vaccineCode,
        @Nullable String doseLabel,
        String regionScope,
        LocalDate startsOn,
        LocalDate endsOn,
        int targetDoses,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
