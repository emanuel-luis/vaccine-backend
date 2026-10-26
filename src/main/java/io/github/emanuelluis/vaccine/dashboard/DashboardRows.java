package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.dashboard.Suppression.Cell;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response rows of the dashboard endpoints; a suppressed cell has a null measure and {@code suppressed = true}. */
public final class DashboardRows {

    private DashboardRows() {}

    /** Rows plus the generation the mart had absorbed, read in the same statement. */
    public record Rows<T>(long batchGeneration, List<T> rows, int suppressed) {
        static <T extends Cell> Rows<T> of(long batchGeneration, List<T> rows) {
            return new Rows<>(batchGeneration, rows, (int)
                    rows.stream().filter(Cell::suppressed).count());
        }

        public static <T> Rows<T> empty() {
            return new Rows<>(0, List.of(), 0);
        }
    }

    public record VaccineDoses(
            String vaccineCode,
            String vaccineName,
            String doseLabel,
            @Nullable Long doses,
            boolean suppressed) implements Cell {}

    /** {@code ageBandYears} is null for the Unknown demographic. */
    public record AgeBandDoses(
            @Nullable Integer ageBandYears, @Nullable Long doses, boolean suppressed) implements Cell {}

    /**
     * One area of the map. {@code code} matches the GeoJSON property {@code codarea}; {@code abbr} is set
     * at state level only; {@code reason} is {@code small_cell}, {@code no_population} or null.
     */
    public record MapArea(
            String code,
            @Nullable String abbr,
            String name,
            @Nullable BigDecimal value,
            @Nullable Long numerator,
            @Nullable BigDecimal denominator,
            boolean suppressed,
            @Nullable String reason)
            implements Cell {}

    public record HealthUnitDoses(
            String cnesCode,
            String unitName,
            @Nullable BigDecimal latitude,
            @Nullable BigDecimal longitude,
            @Nullable Long doses,
            boolean suppressed)
            implements Cell {}

    /** One export row at the grain of mart_doses_daily (CNES unit level). */
    public record DosesDailyRow(
            LocalDate dateDay,
            String stateAbbr,
            int municipalityCode,
            String cnesCode,
            String vaccineCode,
            String doseLabel,
            @Nullable Integer ageBandYears,
            @Nullable Long doses,
            boolean suppressed)
            implements Cell {}
}
