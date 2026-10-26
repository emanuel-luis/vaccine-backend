package io.github.emanuelluis.vaccine.admin;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Data management over Postgres: job queue (202, 409, 404), log paging, the sources of the latest
 * load-reference run, dbt results, reconciliation and ingest statistics. The fixed clock is
 * 2026-10-25T15:00Z. The token is {@link Auth#reader()} (data-admin, user vaccine-smoke).
 */
@SpeedLayerSetup
class AdminIT {

    private static final String BASE = "/api/v1/admin";
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from ops.dbt_invocation").update();
        jdbc.sql("delete from ops.job").update();
        jdbc.sql("delete from ops.load_run").update();
        jdbc.sql("delete from ops.ingest_reject").update();
        jdbc.sql("delete from raw.live_dose").update();
    }

    private ResultActions queue(String kind) throws Exception {
        return mvc.perform(post(BASE + "/jobs")
                .with(Auth.reader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"" + kind + "\"}"));
    }

    private ResultActions getAs(String path) throws Exception {
        return mvc.perform(get(BASE + path).with(Auth.reader()));
    }

    @Test
    void queueingAJobAnswers202ThenASecondOneIs409UntilTheFirstFinishes() throws Exception {
        var result = queue("reconcile")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("queued"))
                .andExpect(jsonPath("$.data.kind").value("reconcile"))
                .andExpect(jsonPath("$.data.requestedBy").value("vaccine-smoke"))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.jobId");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo(BASE + "/jobs/" + id);

        queue("dbt-build")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("job_already_active"));
        queue("nothing").andExpect(status().isBadRequest());

        getAs("/jobs/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(id));
        getAs("/jobs").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));

        // the worker finishes the job (it owns the status): a new one is accepted
        jdbc.sql("""
                        update ops.job set status = 'succeeded', started_at = now(), finished_at = now()
                        where job_id = :id::uuid""").param("id", id).update();
        queue("dbt-build").andExpect(status().isAccepted());
        getAs("/jobs?limit=1")
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].kind").value("dbt-build"));
    }

    @Test
    void unknownJobIs404() throws Exception {
        getAs("/jobs/" + UUID.randomUUID()).andExpect(status().isNotFound());
        getAs("/jobs/" + UUID.randomUUID() + "/log").andExpect(status().isNotFound());
    }

    @Test
    void jobShowsRowsAndRejectsOfItsRun() throws Exception {
        insertRun();
        jdbc.sql("""
                        insert into ops.load_run_table
                            (run_id, table_name, source, rows_read, rows_loaded, rows_rejected, loaded_at)
                        values (:run, 'a', 's1', 10, 8, 2, now()), (:run, 'b', 's2', 5, 5, 0, now())""").param("run", RUN).update();
        insertJob("succeeded", RUN);
        getAs("/jobs/" + JOB)
                .andExpect(jsonPath("$.data.rows").value(13))
                .andExpect(jsonPath("$.data.rejects").value(2))
                .andExpect(jsonPath("$.data.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.data.finishedAt").exists());
    }

    @Test
    void logPagesBySeq() throws Exception {
        insertJob("succeeded", null);
        for (int seq = 1; seq <= 5; seq++) {
            jdbc.sql("insert into ops.job_log (job_id, seq, level, message) values (:job, :seq, 'info', :msg)")
                    .param("job", JOB)
                    .param("seq", seq)
                    .param("msg", "line " + seq)
                    .update();
        }
        getAs("/jobs/" + JOB + "/log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].seq", contains(1, 2, 3, 4, 5)));
        getAs("/jobs/" + JOB + "/log?afterSeq=2&limit=2")
                .andExpect(jsonPath("$.data[*].seq", contains(3, 4)))
                .andExpect(jsonPath("$.data[0].message").value("line 3"));
        getAs("/jobs/" + JOB + "/log?afterSeq=5").andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void sourcesComeFromTheLatestSucceededLoadReferenceRun() throws Exception {
        getAs("/sources")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(nullValue()))
                .andExpect(jsonPath("$.data.sources", hasSize(0)));
        insertRun();
        jdbc.sql("""
                        insert into ops.load_run_source
                            (run_id, source_id, path, table_name, publisher, source_url, license_note,
                             license_status, retrieved_at, snapshot_date, rows, sha256, verified)
                        values
                            (:run, 'ibge_uf', 'data/sources/uf.json.gz', 'raw.ibge_uf', 'IBGE', 'https://example.org/uf',
                             'open', 'confirmed', '2026-01-01', '2026-01-01', 27, repeat('a', 64), true),
                            (:run, 'ibge_mesh', 'data/sources/mesh.json.gz', null, 'IBGE', 'https://example.org/mesh',
                             'attribution', 'to_be_confirmed', '2026-01-02', '2026-01-02', 5570, repeat('b', 64), true)""").param("run", RUN).update();
        getAs("/sources")
                .andExpect(jsonPath("$.data.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.data.sources[*].sourceId", contains("ibge_mesh", "ibge_uf")))
                .andExpect(jsonPath("$.data.sources[0].tableName").value(nullValue()))
                .andExpect(jsonPath("$.data.sources[0].licenseStatus").value("to_be_confirmed"))
                .andExpect(jsonPath("$.data.sources[1].rows").value(27))
                .andExpect(jsonPath("$.data.sources[1].sha256").value("a".repeat(64)));
    }

    @Test
    void dbtResultsFilterByStatusAndType() throws Exception {
        jdbc.sql("""
                        insert into ops.dbt_invocation
                            (invocation_id, command, generated_at, elapsed_s, dbt_version, success, batch_generation)
                        values ('inv-1', 'build', '2026-10-25T14:00:00Z', 12.5, '1.12.0', false, 3)""").update();
        jdbc.sql("""
                        insert into ops.dbt_node_result
                            (invocation_id, unique_id, resource_type, name, status, failures, execution_time_s, message)
                        values
                            ('inv-1', 'model.p.a', 'model', 'a', 'success', null, 1.0, null),
                            ('inv-1', 'model.p.b', 'model', 'b', 'error', null, 0.5, 'boom'),
                            ('inv-1', 'test.p.t1', 'test', 't1', 'pass', 0, 0.1, null),
                            ('inv-1', 'test.p.t2', 'test', 't2', 'fail', 4, 0.2, 'failed')""").update();
        getAs("/dbt/invocations")
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].invocationId").value("inv-1"))
                .andExpect(jsonPath("$.data[0].success").value(false))
                .andExpect(jsonPath("$.data[0].batchGeneration").value(3));
        // problems first, then by id
        getAs("/dbt/invocations/inv-1/results")
                .andExpect(
                        jsonPath("$.data[*].uniqueId", contains("model.p.b", "test.p.t2", "model.p.a", "test.p.t1")));
        getAs("/dbt/invocations/inv-1/results?status=fail")
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].failures").value(4));
        getAs("/dbt/invocations/inv-1/results?resourceType=test")
                .andExpect(jsonPath("$.data[*].uniqueId", contains("test.p.t2", "test.p.t1")));
        getAs("/dbt/invocations/inv-1/results?status=error&resourceType=test")
                .andExpect(jsonPath("$.data", hasSize(0)));
        getAs("/dbt/invocations/inv-1/results?status=bogus").andExpect(status().isBadRequest());
        getAs("/dbt/invocations/missing/results").andExpect(status().isNotFound());
    }

    @Test
    void reconciliationListsRunsWithChecksAndDiffs() throws Exception {
        UUID recon = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        jdbc.sql("""
                        insert into ops.load_run (run_id, command, started_at, finished_at, status)
                        values (:id, 'reconcile', '2026-10-25T14:00:00Z', '2026-10-25T14:00:05Z', 'failed')""").param("id", recon).update();
        jdbc.sql("""
                        insert into ops.reconciliation_check
                            (run_id, check_name, batch_generation, open_generation, keys_compared, keys_mismatched,
                             expected_total, actual_total, passed)
                        values (:id, 'serving_matches_landing', 2, 3, 100, 1, 500, 499, false),
                               (:id, 'live_aggregate_matches_landing', null, 3, 10, 0, 50, 50, true)""").param("id", recon).update();
        jdbc.sql("""
                        insert into ops.reconciliation_diff (run_id, check_name, line_no, diff_key, expected, actual)
                        values (:id, 'serving_matches_landing', 1, '{"day":"2026-10-24"}'::jsonb, 7, 6)""").param("id", recon).update();
        getAs("/reconciliation?limit=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status").value("failed"))
                .andExpect(jsonPath(
                        "$.data[0].checks[*].checkName",
                        contains("live_aggregate_matches_landing", "serving_matches_landing")))
                .andExpect(jsonPath("$.data[0].checks[0].passed").value(true))
                .andExpect(jsonPath("$.data[0].checks[0].diffs", hasSize(0)))
                .andExpect(jsonPath("$.data[0].checks[1].keysMismatched").value(1))
                .andExpect(jsonPath("$.data[0].checks[1].diffs[0].expected").value(7))
                .andExpect(jsonPath("$.data[0].checks[1].diffs[0].diffKey").value("{\"day\": \"2026-10-24\"}"));
    }

    @Test
    void ingestCountsAcceptedPerHourAndRejectsByReasonInTheWindow() throws Exception {
        // now is 15:00Z; hours=2 keeps 13:00Z onwards
        insertDose("00000000-0000-0000-0000-000000000d01", "2026-10-25T14:10:00Z");
        insertDose("00000000-0000-0000-0000-000000000d02", "2026-10-25T14:50:00Z");
        insertDose("00000000-0000-0000-0000-000000000d03", "2026-10-25T13:30:00Z");
        insertDose("00000000-0000-0000-0000-000000000d04", "2026-10-25T09:00:00Z");
        for (String when : new String[] {"2026-10-25T14:00:00Z", "2026-10-25T14:05:00Z", "2026-10-24T01:00:00Z"}) {
            jdbc.sql("""
                            insert into ops.ingest_reject (received_at, client_id, http_status, reason)
                            values (:at::timestamptz, 'c', 422, 'invalid_cnes')""").param("at", when).update();
        }
        getAs("/ingest?hours=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedPerHour", hasSize(2)))
                .andExpect(jsonPath("$.data.acceptedPerHour[0].doses").value(1))
                .andExpect(jsonPath("$.data.acceptedPerHour[1].doses").value(2))
                .andExpect(jsonPath("$.data.rejectsByReason", hasSize(1)))
                .andExpect(jsonPath("$.data.rejectsByReason[0].reason").value("invalid_cnes"))
                .andExpect(jsonPath("$.data.rejectsByReason[0].httpStatus").value(422))
                .andExpect(jsonPath("$.data.rejectsByReason[0].count").value(2));
        getAs("/ingest")
                .andExpect(jsonPath("$.data.acceptedPerHour", hasSize(3)))
                .andExpect(jsonPath("$.data.rejectsByReason[0].count").value(2));
        getAs("/ingest?hours=0").andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminsAreForbidden() throws Exception {
        mvc.perform(get(BASE + "/jobs").with(Auth.as("vaccine-web", "BR", "gestor")))
                .andExpect(status().isForbidden());
    }

    private void insertRun() {
        jdbc.sql("""
                        insert into ops.load_run (run_id, command, started_at, finished_at, status)
                        values (:id, 'load-reference', '2026-10-25T10:00:00Z', '2026-10-25T10:01:00Z', 'succeeded')""").param("id", RUN).update();
    }

    private void insertJob(String status, UUID run) {
        jdbc.sql("""
                        insert into ops.job (job_id, kind, status, requested_by, requested_at, started_at, finished_at, run_id)
                        values (:id, 'load-reference', :status, 'tester', '2026-10-25T09:00:00Z',
                                '2026-10-25T09:00:01Z', '2026-10-25T09:00:30Z', :run)""").param("id", JOB).param("status", status).param("run", run).update();
    }

    private void insertDose(String id, String receivedAt) {
        jdbc.sql("""
                        insert into raw.live_dose
                            (event_id, generation, client_id, idempotency_key, request_sha256, person_id, birth_date,
                             sex, cnes_code, vaccine_code, dose_label, administered_at, received_at)
                        values (:id::uuid, 1, 'sim', :id, repeat('0', 64), :id::uuid, '2026-01-01', 'F', '0000264',
                                'PENTA', 'D1', :at::timestamptz, :at::timestamptz)""").param("id", id).param("at", receivedAt).update();
    }
}
