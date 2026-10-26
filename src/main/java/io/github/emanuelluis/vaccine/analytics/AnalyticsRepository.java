package io.github.emanuelluis.vaccine.analytics;

import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.DosesByAgeBand;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.DosesByStateMonth;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.StateCoverage;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over the dbt analytics marts. Optional filters are passed as typed nulls; the region filter comes from ScopeGuard (state grain only). */
@Repository
@Transactional(readOnly = true)
public class AnalyticsRepository {

    static final String DOSES_BY_STATE_MONTH = "marts.mart_doses_by_state_month";
    static final String DOSES_BY_AGE_BAND = "marts.mart_doses_by_age_band";
    static final String COVERAGE_BY_STATE = "marts.mart_coverage_by_state";

    private final JdbcClient jdbc;

    public AnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DosesByStateMonth> dosesByStateMonth(MonthRange range, RegionFilter region, String vaccine) {
        return jdbc.sql("""
                        select state_abbr, state_name, region_name, year_month, month_start,
                               vaccine_code, dose_label, doses
                        from marts.mart_doses_by_state_month
                        where month_start >= :first_day and month_start < :end_exclusive
                          and (cast(:state as text) is null or state_abbr = cast(:state as text))
                          and (cast(:vaccine as text) is null or vaccine_code = cast(:vaccine as text))
                        order by state_abbr, month_start, vaccine_code, dose_label
                        """)
                .param("first_day", range.firstDay())
                .param("end_exclusive", range.endExclusive())
                .param("state", region.stateAbbr())
                .param("vaccine", vaccine)
                .query(DosesByStateMonth.class)
                .list();
    }

    public List<DosesByAgeBand> dosesByAgeBand(MonthRange range, RegionFilter region, String vaccine) {
        return jdbc.sql("""
                        select state_abbr, year_month, month_start, vaccine_code, sex,
                               age_band_years, doses
                        from marts.mart_doses_by_age_band
                        where month_start >= :first_day and month_start < :end_exclusive
                          and (cast(:state as text) is null or state_abbr = cast(:state as text))
                          and (cast(:vaccine as text) is null or vaccine_code = cast(:vaccine as text))
                        order by state_abbr, month_start, vaccine_code, sex, age_band_years
                        """)
                .param("first_day", range.firstDay())
                .param("end_exclusive", range.endExclusive())
                .param("state", region.stateAbbr())
                .param("vaccine", vaccine)
                .query(DosesByAgeBand.class)
                .list();
    }

    public List<StateCoverage> coverage(Integer year, RegionFilter region, String vaccine) {
        return jdbc.sql("""
                        select state_abbr, state_name, year, vaccine_code, dose_label,
                               target_age_years, doses, target_population, sample_fraction,
                               expected_population, coverage_pct, numerator_is_synthetic,
                               denominator_source
                        from marts.mart_coverage_by_state
                        where (cast(:year as integer) is null or year = cast(:year as integer))
                          and (cast(:state as text) is null or state_abbr = cast(:state as text))
                          and (cast(:vaccine as text) is null or vaccine_code = cast(:vaccine as text))
                        order by year, state_abbr, vaccine_code, dose_label
                        """)
                .param("year", year)
                .param("state", region.stateAbbr())
                .param("vaccine", vaccine)
                .query(StateCoverage.class)
                .list();
    }
}
