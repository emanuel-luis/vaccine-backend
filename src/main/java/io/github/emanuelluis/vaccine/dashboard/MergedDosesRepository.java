package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.time.LocalDate;
import java.util.List;
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
 */
@Repository
@Transactional(readOnly = true)
public class MergedDosesRepository {

    public static final String SOURCE = "marts.mart_doses_daily + live.dose_daily";

    /** Optional filters; a null field means "no filter". */
    public record Filters(RegionFilter region, String vaccine, String dose, Integer ageBand) {}

    public record Summary(long dosesInPeriod, long dosesToday, long batchGeneration, long liveDosesPending) {}

    public record TimeseriesPoint(LocalDate periodStart, long doses) {}

    /** Time series plus the generation the mart had absorbed, read in the same statement. */
    public record Timeseries(long batchGeneration, List<TimeseriesPoint> points) {}

    record TimeseriesRow(LocalDate periodStart, long doses, long batchGeneration) {}

    private static final String FILTER = """
            (cast(:state as text) is null or state_abbr = cast(:state as text))
              and (cast(:municipality as integer) is null or municipality_code = cast(:municipality as integer))
              and (cast(:vaccine as text) is null or vaccine_code = cast(:vaccine as text))
              and (cast(:dose as text) is null or dose_label = cast(:dose as text))
              and (cast(:ageBand as integer) is null or age_band_years = cast(:ageBand as integer))
            """;

    private static final String MERGED = """
            with w as (
                select coalesce((select batch_generation from marts.mart_doses_daily limit 1), 0) as g),
            merged as (
                select date_day as day, doses, 0 as live
                from marts.mart_doses_daily
                where %1$s
                union all
                select l.administered_on, l.doses, 1
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

    public Timeseries timeseries(LocalDate from, LocalDate to, String granularity, Filters f) {
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
        // An empty series carries no row to read W from; the extra read cannot disagree with rows.
        long generation = rows.isEmpty() ? batchGeneration() : rows.getFirst().batchGeneration();
        return new Timeseries(
                generation,
                rows.stream()
                        .map(r -> new TimeseriesPoint(r.periodStart(), r.doses()))
                        .toList());
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
