package io.github.emanuelluis.vaccine.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Row types of the analytics marts; column contracts in docs/data-model.md. */
public final class AnalyticsRows {

    private AnalyticsRows() {}

    /** One row of {@code marts.mart_doses_by_state_month}. */
    public record DosesByStateMonth(
            String stateAbbr,
            String stateName,
            String regionName,
            String yearMonth,
            LocalDate monthStart,
            String vaccineCode,
            String doseLabel,
            long doses) {}

    /** One row of {@code marts.mart_doses_by_age_band}. */
    public record DosesByAgeBand(
            String stateAbbr,
            String yearMonth,
            LocalDate monthStart,
            String vaccineCode,
            String sex,
            int ageBandYears,
            long doses) {}

    /** One row of {@code marts.mart_coverage_by_state}. */
    public record StateCoverage(
            String stateAbbr,
            String stateName,
            int year,
            String vaccineCode,
            String doseLabel,
            int targetAgeYears,
            long doses,
            long targetPopulation,
            BigDecimal sampleFraction,
            BigDecimal expectedPopulation,
            BigDecimal coveragePct,
            boolean numeratorIsSynthetic,
            String denominatorSource) {}
}
