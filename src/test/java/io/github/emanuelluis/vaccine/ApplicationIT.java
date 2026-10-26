package io.github.emanuelluis.vaccine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

/** Whole app against a real Postgres: Flyway migrates, the API reads mart fixtures and ops tables. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

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

    @Test
    void flywayAppliedEveryMigrationAndDroppedTheLegacySchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        assertThat(applied).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.unidade_basica') IS NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('raw.vaccination_event') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void dosesByStateMonthFiltersByRangeStateAndVaccine() throws Exception {
        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-01")
                        .param("to", "2024-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.source").value("marts.mart_doses_by_state_month"))
                .andExpect(jsonPath("$.meta.synthetic").value(true))
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.meta.layer").value("batch"))
                .andExpect(jsonPath("$.meta.suppressedCells").value(0))
                .andExpect(jsonPath("$.meta.batchGeneration").value(nullValue()))
                .andExpect(jsonPath("$.data[0].stateAbbr").value("MA"))
                .andExpect(jsonPath("$.data[0].monthStart").value("2024-03-01"))
                .andExpect(jsonPath("$.data[0].doses").value(12));

        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-01")
                        .param("to", "2025-12")
                        .param("state", "MA")
                        .param("vaccine", "PENTA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[1].yearMonth").value("2025-02"));
    }

    @Test
    void dosesByAgeBandReturnsSexAndAge() throws Exception {
        mvc.perform(get("/api/v1/doses/by-age-band")
                        .with(Auth.reader())
                        .param("from", "2024-03")
                        .param("to", "2024-03")
                        .param("state", "MA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(3))
                .andExpect(jsonPath("$.data[0].sex").value("F"))
                .andExpect(jsonPath("$.data[0].ageBandYears").value(0))
                .andExpect(jsonPath("$.data[2].sex").value("U"))
                .andExpect(jsonPath("$.data[2].ageBandYears").value(nullValue()));
    }

    @Test
    void coverageIsFilteredByYear() throws Exception {
        mvc.perform(get("/api/v1/coverage").with(Auth.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(2));
        mvc.perform(get("/api/v1/coverage")
                        .with(Auth.reader())
                        .param("year", "2025")
                        .param("state", "MA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].coveragePct").value(83.33))
                .andExpect(jsonPath("$.data[0].numeratorIsSynthetic").value(true))
                .andExpect(jsonPath("$.data[0].denominatorSource").value("IBGE Censo 2022, SIDRA 9514"));
    }

    @Test
    void healthUnitHistoryShowsEveryVersion() throws Exception {
        mvc.perform(get("/api/v1/health-units/0000264/history").with(Auth.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.synthetic").value(false))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].unitName").value("USF MARUIM"))
                .andExpect(jsonPath("$.data[0].validTo").value("2026-10-24"))
                .andExpect(jsonPath("$.data[1].isCurrent").value(true))
                .andExpect(jsonPath("$.data[1].municipalityName").value("São Luís"));

        mvc.perform(get("/api/v1/health-units/9999999/history").with(Auth.reader()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void latestRunPerCommandWithTableCounts() throws Exception {
        mvc.perform(get("/api/v1/runs/latest").with(Auth.reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[0].command").value("generate"))
                .andExpect(jsonPath("$.data[0].seed").value(42))
                .andExpect(jsonPath("$.data[0].tables[0].rowsLoaded").value(200))
                .andExpect(jsonPath("$.data[1].command").value("load-reference"));
    }

    @Test
    void missingMartsAnswer503WithAHint() throws Exception {
        jdbc.execute("DROP SCHEMA marts CASCADE");
        mvc.perform(get("/api/v1/coverage").with(Auth.reader()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.detail", containsString("make demo")));
    }

    @Test
    void everyDataEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/coverage"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(post("/api/v1/doses").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/doses/by-state-month").with(Auth.ingest()).param("from", "2024-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void invalidParametersAnswer400() throws Exception {
        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-13")
                        .param("to", "2024-12"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-01")
                        .param("to", "2024-12")
                        .param("state", "ma"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2020-01")
                        .param("to", "2024-12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("36 months")));
    }
}
