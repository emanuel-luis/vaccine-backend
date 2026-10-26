package io.github.emanuelluis.vaccine.campaigns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A {@link Campaign} plus its progress; the progress fields are suppressed like any municipality count. */
public record CampaignDetail(
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
        int version,
        Progress progress) {

    /**
     * Doses of the campaign's vaccine (and dose) inside its region from {@code startsOn} to
     * {@code min(endsOn, today)}. {@code dosesToDate} and {@code pct} are null when {@code suppressed}.
     */
    public record Progress(
            @Nullable Long dosesToDate, @Nullable BigDecimal pct, boolean suppressed, List<DailyPoint> daily) {}

    /** One day of the series; {@code doses} is null when {@code suppressed}. */
    public record DailyPoint(LocalDate date, @Nullable Long doses, boolean suppressed) {}

    static CampaignDetail of(Campaign c, Progress progress) {
        return new CampaignDetail(
                c.campaignId(),
                c.name(),
                c.vaccineCode(),
                c.doseLabel(),
                c.regionScope(),
                c.startsOn(),
                c.endsOn(),
                c.targetDoses(),
                c.createdBy(),
                c.createdAt(),
                c.updatedAt(),
                c.version(),
                progress);
    }
}
