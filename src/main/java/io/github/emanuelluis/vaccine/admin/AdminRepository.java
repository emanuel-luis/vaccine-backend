package io.github.emanuelluis.vaccine.admin;

import io.github.emanuelluis.vaccine.admin.AdminRows.Check;
import io.github.emanuelluis.vaccine.admin.AdminRows.DbtInvocation;
import io.github.emanuelluis.vaccine.admin.AdminRows.DbtNodeResult;
import io.github.emanuelluis.vaccine.admin.AdminRows.Diff;
import io.github.emanuelluis.vaccine.admin.AdminRows.HourDoses;
import io.github.emanuelluis.vaccine.admin.AdminRows.Ingest;
import io.github.emanuelluis.vaccine.admin.AdminRows.Job;
import io.github.emanuelluis.vaccine.admin.AdminRows.JobLogLine;
import io.github.emanuelluis.vaccine.admin.AdminRows.Reconciliation;
import io.github.emanuelluis.vaccine.admin.AdminRows.RejectReason;
import io.github.emanuelluis.vaccine.admin.AdminRows.Source;
import io.github.emanuelluis.vaccine.admin.AdminRows.Sources;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-management reads of schema ops, plus the only write: a {@code queued} row in {@code ops.job}.
 * The worker owns every later status change.
 */
@Repository
@Transactional(readOnly = true)
public class AdminRepository {

    private static final String JOB_SELECT = """
            select j.job_id, j.kind, j.status, j.requested_by, j.requested_at, j.started_at, j.finished_at,
                   j.heartbeat_at, j.run_id, j.dbt_invocation_id, j.error,
                   (select sum(t.rows_loaded)::bigint from ops.load_run_table t where t.run_id = j.run_id) as rows,
                   (select sum(t.rows_rejected)::bigint from ops.load_run_table t where t.run_id = j.run_id) as rejects
            from ops.job j
            """;

    private final JdbcClient jdbc;

    public AdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a queued job and sends {@code NOTIFY job_queued} in the same transaction. A second active
     * job violates {@code ux_job_single_active} (DuplicateKeyException).
     */
    @Transactional
    public Job enqueue(UUID id, String kind, String requestedBy) {
        jdbc.sql("insert into ops.job (job_id, kind, status, requested_by) values (:id, :kind, 'queued', :by)")
                .param("id", id)
                .param("kind", kind)
                .param("by", requestedBy)
                .update();
        // count(*) over the call: pg_notify returns void, which JDBC cannot read as a value
        jdbc.sql("select count(*) from (select pg_notify('job_queued', :id)) n")
                .param("id", id.toString())
                .query(Long.class)
                .single();
        return find(id).orElseThrow();
    }

    public List<Job> jobs(int limit) {
        return jdbc.sql(JOB_SELECT + " order by j.requested_at desc, j.job_id limit :limit")
                .param("limit", limit)
                .query(Job.class)
                .list();
    }

    public Optional<Job> find(UUID id) {
        return jdbc.sql(JOB_SELECT + " where j.job_id = :id")
                .param("id", id)
                .query(Job.class)
                .optional();
    }

    public boolean jobExists(UUID id) {
        return jdbc.sql("select exists (select 1 from ops.job where job_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public List<JobLogLine> log(UUID id, int afterSeq, int limit) {
        return jdbc.sql("""
                        select seq, logged_at, level, message from ops.job_log
                        where job_id = :id and seq > :after order by seq limit :limit
                        """)
                .param("id", id)
                .param("after", afterSeq)
                .param("limit", limit)
                .query(JobLogLine.class)
                .list();
    }

    /** Manifest entries of the latest succeeded load-reference run; no such run gives an empty answer. */
    public Sources sources() {
        record RunRow(UUID runId, OffsetDateTime loadedAt) {}
        Optional<RunRow> run = jdbc.sql("""
                        select run_id, coalesce(finished_at, started_at) as loaded_at
                        from ops.load_run
                        where command = 'load-reference' and status = 'succeeded'
                        order by started_at desc limit 1
                        """).query(RunRow.class).optional();
        if (run.isEmpty()) {
            return new Sources(null, null, List.of());
        }
        List<Source> sources =
                jdbc.sql("""
                        select source_id, path, table_name, publisher, source_url, license_note, license_status,
                               retrieved_at, snapshot_date, rows, sha256, verified
                        from ops.load_run_source where run_id = :id order by source_id
                        """).param("id", run.get().runId()).query(Source.class).list();
        return new Sources(run.get().runId(), run.get().loadedAt(), sources);
    }

    public List<DbtInvocation> dbtInvocations(int limit) {
        return jdbc.sql("""
                        select invocation_id, job_id, command, generated_at, elapsed_s, dbt_version, success,
                               batch_generation
                        from ops.dbt_invocation order by generated_at desc, invocation_id limit :limit
                        """).param("limit", limit).query(DbtInvocation.class).list();
    }

    public boolean invocationExists(String id) {
        return jdbc.sql("select exists (select 1 from ops.dbt_invocation where invocation_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Problems first (error, fail, warn), then the rest by id; either filter may be null. */
    public List<DbtNodeResult> dbtResults(String invocationId, String status, String resourceType) {
        return jdbc.sql("""
                        select unique_id, resource_type, name, status, failures, execution_time_s, message
                        from ops.dbt_node_result
                        where invocation_id = :id
                          and (cast(:status as text) is null or status = cast(:status as text))
                          and (cast(:type as text) is null or resource_type = cast(:type as text))
                        order by case status when 'error' then 0 when 'fail' then 0 when 'warn' then 1 else 2 end,
                                 unique_id
                        """)
                .param("id", invocationId)
                .param("status", status)
                .param("type", resourceType)
                .query(DbtNodeResult.class)
                .list();
    }

    /** The latest reconcile runs, newest first, each with its checks and the diffs of failed checks. */
    public List<Reconciliation> reconciliation(int limit) {
        record RunRow(UUID runId, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}
        record CheckRow(
                UUID runId,
                String checkName,
                Long batchGeneration,
                long openGeneration,
                long keysCompared,
                long keysMismatched,
                long expectedTotal,
                long actualTotal,
                boolean passed) {}
        record DiffRow(UUID runId, String checkName, int lineNo, String diffKey, long expected, long actual) {}
        String latest = "(select run_id from ops.load_run where command = 'reconcile' "
                + "order by started_at desc limit :limit)";
        List<RunRow> runs =
                jdbc.sql("""
                        select run_id, status, started_at, finished_at from ops.load_run
                        where command = 'reconcile' order by started_at desc limit :limit
                        """).param("limit", limit).query(RunRow.class).list();
        List<CheckRow> checks = jdbc.sql("""
                        select run_id, check_name, batch_generation, open_generation, keys_compared, keys_mismatched,
                               expected_total, actual_total, passed
                        from ops.reconciliation_check where run_id in\s""" + latest + " order by check_name")
                .param("limit", limit)
                .query(CheckRow.class)
                .list();
        Map<String, List<Diff>> diffs =
                jdbc.sql("""
                        select run_id, check_name, line_no, diff_key::text as diff_key, expected, actual
                        from ops.reconciliation_diff where run_id in\s""" + latest + " order by line_no").param("limit", limit).query(DiffRow.class).list().stream()
                        .collect(Collectors.groupingBy(
                                d -> d.runId() + "/" + d.checkName(),
                                Collectors.mapping(
                                        d -> new Diff(d.lineNo(), d.diffKey(), d.expected(), d.actual()),
                                        Collectors.toList())));
        Map<UUID, List<Check>> byRun = checks.stream()
                .collect(Collectors.groupingBy(
                        CheckRow::runId,
                        Collectors.mapping(
                                c -> new Check(
                                        c.checkName(),
                                        c.batchGeneration(),
                                        c.openGeneration(),
                                        c.keysCompared(),
                                        c.keysMismatched(),
                                        c.expectedTotal(),
                                        c.actualTotal(),
                                        c.passed(),
                                        diffs.getOrDefault(c.runId() + "/" + c.checkName(), List.of())),
                                Collectors.toList())));
        return runs.stream()
                .map(r -> new Reconciliation(
                        r.runId(), r.status(), r.startedAt(), r.finishedAt(), byRun.getOrDefault(r.runId(), List.of())))
                .toList();
    }

    /** Accepted doses per hour and refusals by reason since {@code since}. */
    public Ingest ingest(OffsetDateTime since) {
        List<HourDoses> accepted =
                jdbc.sql("""
                        select date_trunc('hour', received_at) as hour, count(*) as doses
                        from raw.live_dose where received_at >= :since group by 1 order by 1
                        """).param("since", since).query(HourDoses.class).list();
        List<RejectReason> rejects =
                jdbc.sql("""
                        select reason, http_status, count(*) as count
                        from ops.ingest_reject where received_at >= :since
                        group by reason, http_status order by count(*) desc, reason
                        """).param("since", since).query(RejectReason.class).list();
        return new Ingest(accepted, rejects);
    }
}
