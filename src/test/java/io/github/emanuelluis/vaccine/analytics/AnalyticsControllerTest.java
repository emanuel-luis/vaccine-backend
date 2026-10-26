package io.github.emanuelluis.vaccine.analytics;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.DosesByStateMonth;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.SecurityConfig;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice only (no database): parameter validation, envelope and error mapping. */
@WebMvcTest(AnalyticsController.class)
@Import(SecurityConfig.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalyticsRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // DoseRejectAdvice is a controller advice, so every web slice loads it.
    @MockitoBean
    private io.github.emanuelluis.vaccine.ingest.DoseIngestRepository doseIngestRepository;

    @Test
    void validRequestReturnsTheEnvelope() throws Exception {
        var row = new DosesByStateMonth(
                "MA", "Maranhão", "Nordeste", "2024-03", LocalDate.of(2024, 3, 1), "PENTA", "D1", 12);
        when(repository.dosesByStateMonth(any(), eq(new RegionFilter("MA", null)), isNull()))
                .thenReturn(List.of(row));

        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-01")
                        .param("to", "2024-06")
                        .param("state", "MA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.meta.synthetic").value(true))
                .andExpect(jsonPath("$.data[0].vaccineCode").value("PENTA"));

        verify(repository)
                .dosesByStateMonth(
                        eq(new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2024, 6))),
                        eq(new RegionFilter("MA", null)),
                        isNull());
    }

    @Test
    void malformedParametersAreRejectedBeforeTheDatabase() throws Exception {
        mvc.perform(get("/api/v1/doses/by-age-band")
                        .with(Auth.reader())
                        .param("from", "2024-1")
                        .param("to", "2024-06"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/doses/by-state-month").with(Auth.reader()).param("to", "2024-06"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/coverage").with(Auth.reader()).param("vaccine", "penta; drop table x"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/coverage").with(Auth.reader()).param("year", "1999"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void reversedRangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/doses/by-state-month")
                        .with(Auth.reader())
                        .param("from", "2024-06")
                        .param("to", "2024-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("must not be after")));
    }

    @Test
    void missingMartTableMapsTo503() throws Exception {
        when(repository.coverage(any(), any(), any()))
                .thenThrow(new BadSqlGrammarException(
                        "coverage", "select ...", new SQLException("relation does not exist", "42P01")));

        mvc.perform(get("/api/v1/coverage").with(Auth.reader()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Marts not built"));
    }
}
