package io.github.emanuelluis.vaccine.runs;

import io.github.emanuelluis.vaccine.web.ApiResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Latest ELT run of each command, with per-source row counts (run metadata in schema ops). */
@RestController
@RequestMapping("/api/v1/runs")
public class RunsController {

    /** Rows loaded from one source into one table during a run. */
    public record TableLoad(String tableName, String source, long rowsRead, long rowsLoaded, long rowsRejected) {}

    /** One ELT run; {@code sampleFraction} is set by the synthetic generator only. */
    public record Run(
            UUID runId,
            String command,
            String status,
            @Nullable Long seed,
            @Nullable BigDecimal sampleFraction,
            OffsetDateTime startedAt,
            @Nullable OffsetDateTime finishedAt,
            List<TableLoad> tables) {}

    private record RunRow(
            UUID runId,
            String command,
            String status,
            Long seed,
            BigDecimal sampleFraction,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {}

    private record TableRow(
            UUID runId, String tableName, String source, long rowsRead, long rowsLoaded, long rowsRejected) {}

    private final JdbcClient jdbc;

    public RunsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/latest")
    @Transactional(readOnly = true)
    public ApiResponse<Run> latest() {
        List<RunRow> runs = jdbc.sql("""
                        select distinct on (command)
                               run_id, command, status, seed, sample_fraction, started_at, finished_at
                        from ops.load_run
                        order by command, started_at desc
                        """).query(RunRow.class).list();
        Map<UUID, List<TableLoad>> tables = jdbc.sql("""
                        select run_id, table_name, source, rows_read, rows_loaded, rows_rejected
                        from ops.load_run_table
                        where run_id in (
                            select distinct on (command) run_id
                            from ops.load_run
                            order by command, started_at desc)
                        order by table_name, source
                        """).query(TableRow.class).list().stream()
                .collect(Collectors.groupingBy(
                        TableRow::runId,
                        Collectors.mapping(
                                t -> new TableLoad(
                                        t.tableName(), t.source(), t.rowsRead(), t.rowsLoaded(), t.rowsRejected()),
                                Collectors.toList())));
        List<Run> result = runs.stream()
                .map(r -> new Run(
                        r.runId(),
                        r.command(),
                        r.status(),
                        r.seed(),
                        r.sampleFraction(),
                        r.startedAt(),
                        r.finishedAt(),
                        tables.getOrDefault(r.runId(), List.of())))
                .toList();
        return ApiResponse.of("ops.load_run", false, result);
    }
}
