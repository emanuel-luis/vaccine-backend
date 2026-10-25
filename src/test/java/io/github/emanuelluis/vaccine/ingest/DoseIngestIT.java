package io.github.emanuelluis.vaccine.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.DoseJson;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpeedLayerSetup
class DoseIngestIT {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    private ResultActions ingest(String key, Map<String, Object> body) throws Exception {
        var request = post("/api/v1/doses")
                .with(Auth.ingest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DoseJson.json(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private long liveDoses() {
        return jdbc.queryForObject("select coalesce(sum(doses), 0) from live.dose_daily", Long.class);
    }

    private List<Map<String, Object>> rejects() {
        return jdbc.queryForList("select * from ops.ingest_reject order by reject_id");
    }

    @Test
    void firstRequestIs201AndUpdatesTheAggregate() throws Exception {
        ingest("key-1", DoseJson.valid(PERSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventId").exists())
                .andExpect(jsonPath("$.data.generation").value(1))
                .andExpect(jsonPath("$.data.receivedAt").exists())
                .andExpect(jsonPath("$.meta.source").value("raw.live_dose"))
                .andExpect(jsonPath("$.meta.synthetic").value(true))
                .andExpect(jsonPath("$.meta.layer").value("merged"));

        var row = jdbc.queryForMap("""
                select administered_on, state_abbr, municipality_code, age_band_years, doses, generation
                from live.dose_daily
                """);
        assertThat(row.get("administered_on").toString()).isEqualTo("2026-10-25");
        assertThat(row.get("state_abbr").toString().trim()).isEqualTo("MA");
        assertThat(row.get("municipality_code")).isEqualTo(2111300);
        assertThat(row.get("age_band_years")).isEqualTo(0);
        assertThat(row.get("doses")).isEqualTo(1L);
        assertThat(rejects()).isEmpty();
    }

    @Test
    void sameKeyAndPayloadReplaysTheOriginalResponseWithoutChangingTheAggregate() throws Exception {
        String first = ingest("key-1", DoseJson.valid(PERSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Same instant written with another offset: same canonical hash.
        var sameInstant = DoseJson.with(DoseJson.valid(PERSON), "administeredAt", "2026-10-25T13:00:00Z");
        String replay = ingest("key-1", sameInstant)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(liveDoses()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from raw.live_dose", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyStoreOneDoseAndReplayTheOther() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> post = () -> {
                start.await();
                return ingest("key-race", DoseJson.valid(PERSON))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            var first = pool.submit(post);
            var second = pool.submit(post);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(liveDoses()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from raw.live_dose", Integer.class))
                .isEqualTo(1);
        assertThat(rejects()).isEmpty();
    }

    @Test
    void sameKeyWithAnotherPayloadIs422() throws Exception {
        ingest("key-1", DoseJson.valid(PERSON)).andExpect(status().isCreated());
        ingest("key-1", DoseJson.with(DoseJson.valid(PERSON), "sex", "M"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertThat(liveDoses()).isEqualTo(1);
    }

    @Test
    void sameDoseUnderAnotherKeyIs409() throws Exception {
        ingest("key-1", DoseJson.valid(PERSON)).andExpect(status().isCreated());
        ingest("key-2", DoseJson.valid(PERSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dose_already_registered"));
        assertThat(liveDoses()).isEqualTo(1);
        assertThat(rejects()).hasSize(1);
        assertThat(rejects().get(0)).containsEntry("http_status", 409);
    }

    static Stream<Arguments> invalidDoses() {
        var ok = DoseJson.valid(PERSON);
        return Stream.of(
                Arguments.of("administered_in_future", DoseJson.with(ok, "administeredAt", "2026-10-25T15:06:00Z")),
                Arguments.of("administered_too_old", DoseJson.with(ok, "administeredAt", "2026-09-25T14:59:00Z")),
                Arguments.of("unknown_vaccine_dose", DoseJson.with(ok, "doseLabel", "D9")),
                Arguments.of("age_out_of_range", DoseJson.with(ok, "birthDate", "2026-10-20")),
                Arguments.of(
                        "age_out_of_range",
                        DoseJson.with(DoseJson.with(ok, "birthDate", "2026-10-26"), "doseLabel", "D1")),
                Arguments.of(
                        "age_band_out_of_range",
                        DoseJson.with(DoseJson.with(ok, "vaccineCode", "BCG"), "birthDate", "2018-01-01")),
                Arguments.of("unknown_unit", DoseJson.with(ok, "cnesCode", "0000777")),
                Arguments.of("unit_inactive", DoseJson.with(ok, "cnesCode", "0000001")),
                Arguments.of("unit_municipality_unknown", DoseJson.with(ok, "cnesCode", "0000002")),
                Arguments.of("unknown_lot", DoseJson.with(ok, "lotNumber", "NOPE")),
                Arguments.of("lot_vaccine_mismatch", DoseJson.with(ok, "lotNumber", "LOT-ROTA-OK")),
                Arguments.of("lot_expired", DoseJson.with(ok, "lotNumber", "LOT-PENTA-OLD")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDoses")
    void each422RuleAnswersItsCodeAndRecordsAReject(String code, Map<String, Object> body) throws Exception {
        ingest("key-422", body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.field").exists());
        assertThat(liveDoses()).isZero();
        var rejects = rejects();
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0))
                .containsEntry("client_id", "anonymous")
                .containsEntry("idempotency_key", "key-422")
                .containsEntry("http_status", 422)
                .containsEntry("reason", code);
    }

    @Test
    void anOkDoseWithAValidLotIsAccepted() throws Exception {
        ingest("key-lot", DoseJson.with(DoseJson.valid(PERSON), "lotNumber", "LOT-PENTA-OK"))
                .andExpect(status().isCreated());
    }

    @Test
    void aSeriesMustBeGivenInDoseOrder() throws Exception {
        var d2 = DoseJson.with(DoseJson.valid(PERSON), "doseLabel", "D2");
        var d1Later = DoseJson.with(DoseJson.valid(PERSON), "administeredAt", "2026-10-25T11:00:00-03:00");
        ingest("key-d2", d2).andExpect(status().isCreated());
        ingest("key-d1", d1Later)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("series_order_violation"));
        var d1Earlier = DoseJson.with(DoseJson.valid(PERSON), "administeredAt", "2026-10-25T09:00:00-03:00");
        ingest("key-d1b", d1Earlier).andExpect(status().isCreated());
    }

    @Test
    void badKeysAndBodiesAre400AndRecorded() throws Exception {
        var ok = DoseJson.valid(PERSON);
        ingest(null, ok).andExpect(status().isBadRequest());
        ingest("bad key!", ok).andExpect(status().isBadRequest());
        ingest("k".repeat(65), ok).andExpect(status().isBadRequest());
        ingest("key-1", DoseJson.with(ok, "sex", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        ingest("key-1", DoseJson.with(ok, "cnesCode", null)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/doses")
                        .with(Auth.ingest())
                        .header("Idempotency-Key", "key-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_body"));
        assertThat(rejects()).hasSize(6);
        assertThat(jdbc.queryForObject(
                        "select count(*) from ops.ingest_reject where idempotency_key is null", Integer.class))
                .isEqualTo(3);
        assertThat(liveDoses()).isZero();
    }

    @Test
    void missingMartsAnswer503WithRetryAfter() throws Exception {
        jdbc.execute("DROP SCHEMA marts CASCADE");
        ingest("key-503", DoseJson.valid(PERSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"));
        assertThat(jdbc.queryForObject("select count(*) from raw.live_dose", Integer.class))
                .isZero();
    }
}
