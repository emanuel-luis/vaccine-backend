package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.dashboard.DashboardRows.AgeBandDoses;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.DosesDailyRow;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.HealthUnitDoses;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.MapArea;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.Rows;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.VaccineDoses;
import io.github.emanuelluis.vaccine.dashboard.Suppression.Cell;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serving merge of docs/WEB_PLATFORM_PLAN.md, section 3.1: the batch mart plus the live aggregate
 * rows above the generation the mart absorbed, in one statement (one snapshot).
 *
 * <p>READ COMMITTED on purpose: a REPEATABLE READ snapshot is taken before the statement waits for
 * the lock dbt holds while it swaps the mart, so the swapped-in mart would look empty and batch
 * doses would vanish from the merge. Each method is one statement, so it sees one snapshot anyway.
 *
 * <p>Small-number suppression ({@link Suppression}) is applied here, when the rows are built.
 */
@Repository
@Transactional(readOnly = true)
public class MergedDosesRepository {

    public static final String SOURCE = "marts.mart_doses_daily + live.dose_daily";

    /** Optional filters; a null field means "no filter". */
    public record Filters(RegionFilter region, String vaccine, String dose, Integer ageBand) {}

    public record Summary(long dosesInPeriod, long dosesToday, long batchGeneration, long liveDosesPending) {}

    /** {@code doses} is null when the point is suppressed. */
    public record TimeseriesPoint(
            LocalDate periodStart, @Nullable Long doses, boolean suppressed) implements Cell {}

    /**
     * Map request: states or municipalities of {@code areas}; coverage (needs an indicator
     * {@code vaccine} and {@code dose}; {@code from..to} is the year) or plain doses.
     */
    public record MapRequest(
            boolean stateLevel,
            boolean coverage,
            LocalDate from,
            LocalDate to,
            RegionFilter areas,
            String vaccine,
            String dose,
            Integer ageBand) {}

    // Raw statement rows; each carries the generation so an answer needs one statement only.
    record TimeseriesRow(LocalDate periodStart, long doses, long batchGeneration) {}

    record VaccineRow(String vaccineCode, String vaccineName, String doseLabel, long doses, long batchGeneration) {}

    record AgeBandRow(Integer ageBandYears, long doses, long batchGeneration) {}

    record MapRow(String code, String abbr, String name, long doses, BigDecimal expected, long batchGeneration) {}

    record UnitRow(
            String cnesCode,
            String unitName,
            BigDecimal latitude,
            BigDecimal longitude,
            long doses,
            long batchGeneration) {}

    record DailyRow(
            LocalDate dateDay,
            String stateAbbr,
            int municipalityCode,
            String cnesCode,
            String vaccineCode,
            String doseLabel,
            Integer ageBandYears,
            long doses,
            long batchGeneration) {}

    private static final String FILTER = """
            (cast(:state as text) is null or state_abbr = cast(:state as text))
              and (cast(:municipality as integer) is null or municipality_code = cast(:municipality as integer))
              and (cast(:vaccine as text) is null or vaccine_code = cast(:vaccine as text))
              and (cast(:dose as text) is null or dose_label = cast(:dose as text))
              and (cast(:ageBand as integer) is null or age_band_years = cast(:ageBand as integer))
            """;

    /** The region part of the filter, over dim_municipality (the map's areas). */
    private static final String AREA_FILTER = """
            (cast(:state as text) is null or state_abbr = cast(:state as text))
              and (cast(:municipality as integer) is null or municipality_code = cast(:municipality as integer))
            """;

    private static final String MERGED = """
            with w as (
                select coalesce((select batch_generation from marts.mart_doses_daily limit 1), 0) as g),
            merged as (
                select date_day as day, state_abbr, municipality_code, cnes_code, vaccine_code, dose_label,
                       age_band_years, doses, 0 as live
                from marts.mart_doses_daily
                where %1$s
                union all
                select l.administered_on, l.state_abbr, l.municipality_code, cast(l.cnes_code as text),
                       l.vaccine_code, l.dose_label, cast(l.age_band_years as integer), l.doses, 1
                from live.dose_daily l cross join w
                where l.generation > w.g
                  and %2$s)
            """.formatted(
                    FILTER,
                    FILTER.replace("state_abbr", "l.state_abbr")
                            .replace("municipality_code", "l.municipality_code")
                            .replace("vaccine_code", "l.vaccine_code")
                            .replace("dose_label", "l.dose_label")
                            .replace("age_band_years", "l.age_band_years"));

    private final JdbcClient jdbc;

    public MergedDosesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(LocalDate from, LocalDate to, LocalDate today, Filters f) {
        return bind(jdbc.sql(MERGED + """
                        select coalesce(sum(doses) filter (where day between :from and :to), 0) as doses_in_period,
                               coalesce(sum(doses) filter (where day = :today), 0) as doses_today,
                               (select g from w) as batch_generation,
                               coalesce(sum(doses) filter (where live = 1), 0) as live_doses_pending
                        from merged
                        """), f)
                .param("from", from)
                .param("to", to)
                .param("today", today)
                .query(Summary.class)
                .single();
    }

    public Rows<TimeseriesPoint> timeseries(LocalDate from, LocalDate to, String granularity, Filters f) {
        List<TimeseriesRow> rows = bind(jdbc.sql(MERGED + """
                        select cast(date_trunc(cast(:granularity as text), cast(day as timestamp)) as date)
                                   as period_start,
                               sum(doses) as doses,
                               (select g from w) as batch_generation
                        from merged
                        where day between :from and :to
                        group by 1
                        order by 1
                        """), f)
                .param("from", from)
                .param("to", to)
                .param("granularity", granularity)
                .query(TimeseriesRow.class)
                .list();
        boolean fine = Suppression.fine(f.region());
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream()
                        .map(r -> {
                            boolean hide = Suppression.applies(r.doses(), fine);
                            return new TimeseriesPoint(r.periodStart(), hide ? null : r.doses(), hide);
                        })
                        .toList());
    }

    public Rows<VaccineDoses> byVaccine(LocalDate from, LocalDate to, Filters f) {
        List<VaccineRow> rows = bind(jdbc.sql(MERGED + """
                        select a.vaccine_code, coalesce(v.vaccine_name, a.vaccine_code) as vaccine_name,
                               a.dose_label, a.doses, a.batch_generation
                        from (select vaccine_code, dose_label, sum(doses) as doses,
                                     (select g from w) as batch_generation
                              from merged
                              where day between :from and :to
                              group by vaccine_code, dose_label) a
                        left join marts.dim_vaccine v
                               on v.vaccine_code = a.vaccine_code and v.dose_label = a.dose_label
                        order by a.vaccine_code, v.dose_number, a.dose_label
                        """), f)
                .param("from", from)
                .param("to", to)
                .query(VaccineRow.class)
                .list();
        boolean fine = Suppression.fine(f.region());
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream()
                        .map(r -> {
                            boolean hide = Suppression.applies(r.doses(), fine);
                            return new VaccineDoses(
                                    r.vaccineCode(), r.vaccineName(), r.doseLabel(), hide ? null : r.doses(), hide);
                        })
                        .toList());
    }

    public Rows<AgeBandDoses> byAgeBand(LocalDate from, LocalDate to, Filters f) {
        List<AgeBandRow> rows = bind(jdbc.sql(MERGED + """
                        select age_band_years, sum(doses) as doses, (select g from w) as batch_generation
                        from merged
                        where day between :from and :to
                        group by age_band_years
                        order by age_band_years nulls last
                        """), f)
                .param("from", from)
                .param("to", to)
                .query(AgeBandRow.class)
                .list();
        boolean fine = Suppression.fine(f.region());
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream()
                        .map(r -> {
                            boolean hide = Suppression.applies(r.doses(), fine);
                            return new AgeBandDoses(r.ageBandYears(), hide ? null : r.doses(), hide);
                        })
                        .toList());
    }

    /**
     * One row per state or municipality of {@code areas}, zeros included. Coverage divides the merged
     * doses of the year at most the indicator's target age by the expected population (state
     * denominators are sums of the municipal ones). Municipality cells are suppressed on the numerator.
     */
    public Rows<MapArea> map(MapRequest q) {
        Integer maxAge = q.coverage() ? coverageTargetAge(q.vaccine(), q.dose()) : null;
        String areas = q.stateLevel() ? """
                select lpad(cast(min(state_code) as text), 2, '0') as code, cast(state_abbr as text) as abbr,
                       min(state_name) as name, cast(state_abbr as text) as k
                from marts.dim_municipality
                where municipality_code <> 0 and %s
                group by state_abbr
                """ : """
                select lpad(cast(municipality_code as text), 7, '0') as code, cast(null as text) as abbr,
                       municipality_name as name, cast(municipality_code as text) as k
                from marts.dim_municipality
                where municipality_code <> 0 and %s
                """;
        String key = q.stateLevel() ? "cast(state_abbr as text)" : "cast(municipality_code as text)";
        String sql = MERGED + ", area as (" + areas.formatted(AREA_FILTER) + """
                ), n as (
                    select %1$s as k, sum(doses) as doses
                    from merged
                    where day between :from and :to
                      and (cast(:maxAge as integer) is null or age_band_years <= cast(:maxAge as integer))
                    group by 1
                ), d as (
                    select %1$s as k, sum(expected_population) as expected
                    from marts.mart_coverage_target_by_municipality
                    where vaccine_code = cast(:vaccine as text) and dose_label = cast(:dose as text)
                    group by 1
                )
                select a.code, a.abbr, a.name, coalesce(n.doses, 0) as doses, d.expected,
                       (select g from w) as batch_generation
                from area a
                left join n on n.k = a.k
                left join d on d.k = a.k
                order by a.code
                """.formatted(key);
        List<MapRow> rows = bind(jdbc.sql(sql), new Filters(q.areas(), q.vaccine(), q.dose(), q.ageBand()))
                .param("from", q.from())
                .param("to", q.to())
                .param("maxAge", maxAge)
                .query(MapRow.class)
                .list();
        boolean fine = !q.stateLevel();
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream().map(r -> mapArea(r, fine, q.coverage())).toList());
    }

    private static MapArea mapArea(MapRow r, boolean fine, boolean coverage) {
        if (Suppression.applies(r.doses(), fine)) {
            BigDecimal den = coverage ? tenths(r.expected()) : null;
            return new MapArea(r.code(), r.abbr(), r.name(), null, null, den, true, Suppression.SMALL_CELL);
        }
        if (!coverage) {
            return new MapArea(r.code(), r.abbr(), r.name(), BigDecimal.valueOf(r.doses()), null, null, false, null);
        }
        BigDecimal expected = r.expected() == null ? BigDecimal.ZERO : r.expected();
        if (expected.signum() <= 0) {
            return new MapArea(r.code(), r.abbr(), r.name(), null, r.doses(), tenths(expected), false, "no_population");
        }
        BigDecimal pct = BigDecimal.valueOf(r.doses())
                .multiply(BigDecimal.valueOf(100))
                .divide(expected, 1, RoundingMode.HALF_UP);
        return new MapArea(r.code(), r.abbr(), r.name(), pct, r.doses(), tenths(expected), false, null);
    }

    private static BigDecimal tenths(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }

    /** Target age of an indicator dose; 400 when the dose is not a coverage indicator. */
    private int coverageTargetAge(String vaccine, String dose) {
        return jdbc.sql("""
                        select coverage_target_age_years from marts.dim_vaccine
                        where vaccine_code = :vaccine and dose_label = :dose
                          and is_coverage_indicator and coverage_target_age_years is not null
                        """)
                .param("vaccine", vaccine)
                .param("dose", dose)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new InvalidRequestException(
                        "coverage needs `vaccine` and `dose` of a coverage indicator dose (see /reference/vaccines)"));
    }

    /**
     * Active units of the municipality in {@code f.region()} (current version) with their merged doses in
     * the period, most doses first.
     */
    public Rows<HealthUnitDoses> healthUnits(LocalDate from, LocalDate to, Filters f) {
        List<UnitRow> rows = bind(jdbc.sql(MERGED + """
                        , d as (
                            select cnes_code, sum(doses) as doses
                            from merged
                            where day between :from and :to
                            group by cnes_code)
                        select u.cnes_code, u.unit_name, u.latitude, u.longitude,
                               coalesce(d.doses, 0) as doses, (select g from w) as batch_generation
                        from marts.dim_health_unit u
                        left join d on d.cnes_code = u.cnes_code
                        where u.is_current and u.is_active
                          and u.municipality_code = cast(:municipality as integer)
                        order by doses desc, u.unit_name, u.cnes_code
                        """), f)
                .param("from", from)
                .param("to", to)
                .query(UnitRow.class)
                .list();
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream()
                        .map(r -> {
                            boolean hide = Suppression.applies(r.doses(), true);
                            return new HealthUnitDoses(
                                    r.cnesCode(),
                                    r.unitName(),
                                    r.latitude(),
                                    r.longitude(),
                                    hide ? null : r.doses(),
                                    hide);
                        })
                        .toList());
    }

    /** Export grain (one row per date, unit, vaccine, dose and age band); at most {@code limit} rows. */
    public Rows<DosesDailyRow> dosesDaily(LocalDate from, LocalDate to, Filters f, int limit) {
        List<DailyRow> rows = bind(jdbc.sql(MERGED + """
                        select day as date_day, cast(state_abbr as text) as state_abbr, municipality_code,
                               cnes_code, vaccine_code, dose_label, age_band_years, sum(doses) as doses,
                               (select g from w) as batch_generation
                        from merged
                        where day between :from and :to
                        group by day, state_abbr, municipality_code, cnes_code, vaccine_code, dose_label,
                                 age_band_years
                        order by day, municipality_code, cnes_code, vaccine_code, dose_label, age_band_years
                        limit :limit
                        """), f)
                .param("from", from)
                .param("to", to)
                .param("limit", limit)
                .query(DailyRow.class)
                .list();
        return Rows.of(
                generation(rows.isEmpty() ? null : rows.getFirst().batchGeneration()),
                rows.stream()
                        .map(r -> {
                            boolean hide = Suppression.applies(r.doses(), true);
                            return new DosesDailyRow(
                                    r.dateDay(),
                                    r.stateAbbr(),
                                    r.municipalityCode(),
                                    r.cnesCode(),
                                    r.vaccineCode(),
                                    r.doseLabel(),
                                    r.ageBandYears(),
                                    hide ? null : r.doses(),
                                    hide);
                        })
                        .toList());
    }

    // An empty answer carries no row to read W from; the extra read cannot disagree with rows.
    private long generation(Long fromRows) {
        return fromRows != null ? fromRows : batchGeneration();
    }

    /** Generation absorbed by the marts (0 when the mart is empty). */
    public long batchGeneration() {
        return jdbc.sql("select coalesce((select batch_generation from marts.mart_doses_daily limit 1), 0)")
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filters f) {
        return spec.param("state", f.region().stateAbbr())
                .param("municipality", f.region().municipalityCode())
                .param("vaccine", f.vaccine())
                .param("dose", f.dose())
                .param("ageBand", f.ageBand());
    }
}
