package io.github.emanuelluis.vaccine.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.DoseJson;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The serving merge: a live dose counts once, before and after the batch absorbs it. */
@SpeedLayerSetup
class MergedDosesIT {

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

    private void ingest(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/api/v1/doses")
                            .with(Auth.ingest())
                            .header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DoseJson.json(DoseJson.valid(UUID.randomUUID()))))
                    .andExpect(status().isCreated());
        }
    }

    private ResultActions summary(String extra) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/summary?from=2026-10-01&to=2026-10-31" + extra)
                .with(Auth.reader()));
    }

    /** What the dbt build does: absorb the closed generations into the mart and stamp its generation. */
    private long closeAndAbsorb() {
        long w = jdbc.queryForObject("select ops.close_live_generation('test')", Long.class);
        jdbc.update("update marts.mart_doses_daily set batch_generation = ?", w);
        jdbc.update("""
                        insert into marts.mart_doses_daily
                        select administered_on, state_abbr, municipality_code, cnes_code, vaccine_code, dose_label,
                               age_band_years, doses, ?
                        from live.dose_daily where generation <= ?
                        """, w, w);
        return w;
    }

    @Test
    void summaryTotalIsTheSameBeforeAndAfterAbsorption() throws Exception {
        summary("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dosesInPeriod").value(45))
                .andExpect(jsonPath("$.data.dosesToday").value(0))
                .andExpect(jsonPath("$.data.today").value("2026-10-25"))
                .andExpect(jsonPath("$.data.batchGeneration").value(0))
                .andExpect(jsonPath("$.meta.layer").value("merged"));

        ingest(3);
        summary("")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(48))
                .andExpect(jsonPath("$.data.dosesToday").value(3))
                .andExpect(jsonPath("$.data.liveDosesPending").value(3));
        summary("&region=UF:MA").andExpect(jsonPath("$.data.dosesInPeriod").value(18));
        summary("&region=UF:SP").andExpect(jsonPath("$.data.dosesInPeriod").value(30));
        summary("&region=MUN:2111300&vaccine=PENTA&dose=D1&ageBand=0")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(13));

        long w = closeAndAbsorb();
        summary("")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(48))
                .andExpect(jsonPath("$.data.dosesToday").value(3))
                .andExpect(jsonPath("$.data.liveDosesPending").value(0))
                .andExpect(jsonPath("$.data.batchGeneration").value(w))
                .andExpect(jsonPath("$.meta.batchGeneration").value(w));

        ingest(1);
        summary("")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(49))
                .andExpect(jsonPath("$.data.liveDosesPending").value(1));
    }

    @Test
    void timeseriesGroupsByDayWeekAndMonth() throws Exception {
        ingest(2);
        mvc.perform(get("/api/v1/dashboard/doses/timeseries?from=2026-10-01&to=2026-10-31")
                        .with(Auth.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.layer").value("merged"))
                .andExpect(jsonPath("$.meta.count").value(3))
                .andExpect(jsonPath("$.data[0].periodStart").value("2026-10-20"))
                .andExpect(jsonPath("$.data[0].doses").value(10))
                .andExpect(jsonPath("$.data[1].periodStart").value("2026-10-24"))
                .andExpect(jsonPath("$.data[1].doses").value(35))
                .andExpect(jsonPath("$.data[2].periodStart").value("2026-10-25"))
                .andExpect(jsonPath("$.data[2].doses").value(2));
        mvc.perform(get("/api/v1/dashboard/doses/timeseries?from=2026-10-01&to=2026-10-31&granularity=month")
                        .with(Auth.reader()))
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].periodStart").value("2026-10-01"))
                .andExpect(jsonPath("$.data[0].doses").value(47));
        mvc.perform(get("/api/v1/dashboard/doses/timeseries?from=2026-10-01&to=2026-10-31&granularity=week")
                        .with(Auth.reader()))
                .andExpect(jsonPath("$.data[0].periodStart").value("2026-10-19"))
                .andExpect(jsonPath("$.data[0].doses").value(47));
    }

    @Test
    void badParametersAre400() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary?from=2026-10-31&to=2026-10-01")
                        .with(Auth.reader()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/summary?from=2020-01-01&to=2026-10-01")
                        .with(Auth.reader()))
                .andExpect(status().isBadRequest());
        summary("&ageBand=7").andExpect(status().isBadRequest());
        summary("&region=XX:1").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/doses/timeseries?from=2026-10-01&to=2026-10-31&granularity=year")
                        .with(Auth.reader()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/summary?from=2026-10-01").with(Auth.reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingMartsAnswer503WithRetryAfter() throws Exception {
        jdbc.execute("DROP SCHEMA marts CASCADE");
        summary("").andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"));
    }
}
