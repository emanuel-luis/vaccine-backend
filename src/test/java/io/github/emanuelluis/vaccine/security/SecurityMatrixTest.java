package io.github.emanuelluis.vaccine.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRepository;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.ingest.DoseIngestRepository;
import io.github.emanuelluis.vaccine.ingest.DoseValidator;
import io.github.emanuelluis.vaccine.live.LiveEventHub;
import io.github.emanuelluis.vaccine.units.HealthUnitController.HealthUnitVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every endpoint against every kind of caller, on the web slice (no database): URL rules in
 * {@link SecurityConfig} plus the region scope checks of the controllers. Repositories are mocks
 * that answer "no rows", so an allowed call is a 200 (or the 400/404 of an empty request).
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityMatrixTest {

    // Callers, in the order of the expected statuses below.
    private static final String[] CALLERS = {
        "no token", "profissional MUN", "profissional UF", "gestor BR", "data-admin BR", "dose-ingest"
    };

    private static JwtRequestPostProcessor caller(int i) {
        return switch (i) {
            case 1 -> Auth.as("vaccine-web", "MUN:2927408", "profissional");
            case 2 -> Auth.as("vaccine-web", "UF:BA", "profissional");
            case 3 -> Auth.as("vaccine-web", "BR", "gestor");
            case 4 -> Auth.as("vaccine-web", "BR", "data-admin");
            case 5 -> Auth.as("vaccine-simulator", null, "dose-ingest");
            default -> null;
        };
    }

    private static final String RANGE = "from=2026-10-01&to=2026-10-31";
    private static final String MONTHS = "from=2026-01&to=2026-06";

    static Stream<Arguments> matrix() {
        // path, then the status for each caller in CALLERS order
        return Stream.of(
                row("GET /api/v1/me", 401, 200, 200, 200, 200, 403),
                row("GET /api/v1/dashboard/summary?" + RANGE, 401, 200, 200, 200, 200, 403),
                row("GET /api/v1/dashboard/doses/timeseries?" + RANGE, 401, 200, 200, 200, 200, 403),
                row("GET /api/v1/stream", 401, 200, 200, 200, 200, 403),
                // state grain (S>=UF): municipality callers are out
                row("GET /api/v1/doses/by-state-month?" + MONTHS, 401, 403, 200, 200, 200, 403),
                row("GET /api/v1/doses/by-age-band?" + MONTHS, 401, 403, 200, 200, 200, 403),
                row("GET /api/v1/coverage", 401, 403, 200, 200, 200, 403),
                // the unit is in Salvador (BA): inside every scope used here
                row("GET /api/v1/health-units/0000264/history", 401, 200, 200, 200, 200, 403),
                row("GET /api/v1/runs/latest", 401, 403, 403, 403, 200, 403),
                // dose-ingest passes authorization; the empty body is then rejected with 400
                row("POST /api/v1/doses", 401, 403, 403, 403, 403, 400),
                // not under /api/v1: denied to everyone
                row("GET /internal/anything", 401, 403, 403, 403, 403, 403));
    }

    private static Arguments row(String endpoint, int... statuses) {
        return Arguments.of(endpoint, statuses);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AnalyticsRepository analytics;

    @MockitoBean
    private MergedDosesRepository merged;

    @MockitoBean
    private DoseValidator validator;

    @MockitoBean
    private DoseIngestRepository ingest;

    @MockitoBean
    private LiveEventHub hub;

    @MockitoBean
    private JdbcClient jdbc;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stubRepositories() {
        when(merged.summary(any(), any(), any(), any())).thenReturn(new MergedDosesRepository.Summary(0, 0, 0, 0));
        when(merged.timeseries(any(), any(), anyString(), any()))
                .thenReturn(new MergedDosesRepository.Timeseries(0, List.of()));
        // JdbcClient: sql(..).param(..).query(X).list() answers one unit for the unit query, nothing else.
        var spec = mock(JdbcClient.StatementSpec.class);
        var empty = mock(JdbcClient.MappedQuerySpec.class);
        var units = mock(JdbcClient.MappedQuerySpec.class);
        when(empty.list()).thenReturn(List.of());
        when(units.list()).thenReturn(List.of(salvadorUnit()));
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(any(Class.class))).thenReturn(empty);
        when(spec.query(HealthUnitVersion.class)).thenReturn(units);
    }

    private static HealthUnitVersion salvadorUnit() {
        return new HealthUnitVersion(
                "0000264",
                "USF EXEMPLO",
                "CENTRO",
                "RUA A",
                2,
                "Unidade",
                true,
                2927408,
                "Salvador",
                "BA",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(9999, 12, 31),
                true);
    }

    private static MockHttpServletRequestBuilder request(String endpoint) {
        String[] parts = endpoint.split(" ", 2);
        if (parts[0].equals("POST")) {
            return post(parts[1])
                    .header("Idempotency-Key", "matrix-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}");
        }
        return get(parts[1]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void everyEndpointByCaller(String endpoint, int[] expected) throws Exception {
        for (int i = 0; i < CALLERS.length; i++) {
            var request = request(endpoint);
            var post = caller(i);
            if (post != null) {
                request.with(post);
            }
            int actual = mvc.perform(request).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(actual)
                    .as("%s as %s", endpoint, CALLERS[i])
                    .isEqualTo(expected[i]);
        }
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        // not mapped in this slice (no actuator, no springdoc): the point is 404, not 401/403
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedAnswersAreProblemDetailsWithACode() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/v1/runs/latest").with(caller(3)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void regionParameterIsIntersectedWithTheScope() throws Exception {
        String summary = "/api/v1/dashboard/summary?" + RANGE;
        // municipality caller: only its own municipality
        mvc.perform(get(summary + "&region=MUN:2927408").with(caller(1))).andExpect(status().isOk());
        mvc.perform(get(summary + "&region=UF:BA").with(caller(1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("region_out_of_scope"));
        mvc.perform(get(summary + "&region=BR").with(caller(1))).andExpect(status().isForbidden());
        // state caller: its state and the municipalities inside it
        mvc.perform(get(summary + "&region=UF:BA").with(caller(2))).andExpect(status().isOk());
        mvc.perform(get(summary + "&region=MUN:2927408").with(caller(2))).andExpect(status().isOk());
        mvc.perform(get(summary + "&region=UF:SP").with(caller(2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("region_out_of_scope"));
        mvc.perform(get(summary + "&region=MUN:3550308").with(caller(2))).andExpect(status().isForbidden());
        // national caller: anywhere
        mvc.perform(get(summary + "&region=UF:SP").with(caller(3))).andExpect(status().isOk());
        // syntax errors stay 400
        mvc.perform(get(summary + "&region=sp").with(caller(3))).andExpect(status().isBadRequest());
    }

    @Test
    void stateCallerReadsOnlyItsOwnState() throws Exception {
        mvc.perform(get("/api/v1/coverage?state=BA").with(caller(2))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/coverage?state=SP").with(caller(2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("region_out_of_scope"));
    }

    @Test
    void aTokenWithoutRegionScopeIsForbiddenOnScopedEndpoints() throws Exception {
        var noScope = Auth.as("vaccine-web", null, "gestor");
        mvc.perform(get("/api/v1/dashboard/summary?" + RANGE).with(noScope))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("missing_region_scope"));
        mvc.perform(get("/api/v1/stream").with(noScope)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/coverage").with(Auth.as("vaccine-web", "nowhere", "gestor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("missing_region_scope"));
        // /me describes the token, so it answers without a scope
        mvc.perform(get("/api/v1/me").with(noScope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").doesNotExist());
    }

    @Test
    void healthUnitOutsideTheScopeIsForbidden() throws Exception {
        String path = "/api/v1/health-units/0000264/history";
        mvc.perform(get(path).with(Auth.as("vaccine-web", "MUN:2910800", "profissional")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("region_out_of_scope"));
        mvc.perform(get(path).with(Auth.as("vaccine-web", "UF:SP", "gestor"))).andExpect(status().isForbidden());
    }

    @Test
    void meDescribesTheCaller() throws Exception {
        mvc.perform(get("/api/v1/me").with(Auth.as("profissional.salvador", "MUN:2927408", "profissional")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("profissional.salvador"))
                .andExpect(jsonPath("$.data.roles[0]").value("profissional"))
                .andExpect(jsonPath("$.data.scope.level").value("MUN"))
                .andExpect(jsonPath("$.data.scope.state").value("BA"))
                .andExpect(jsonPath("$.data.scope.municipalityCode").value(2927408))
                .andExpect(jsonPath("$.data.scope.label").value("Município: 2927408"));
        mvc.perform(get("/api/v1/me").with(caller(3)))
                .andExpect(jsonPath("$.data.scope.level").value("BR"))
                .andExpect(jsonPath("$.data.scope.label").value("Brasil"))
                .andExpect(jsonPath("$.data.scope.state").doesNotExist());
        mvc.perform(get("/api/v1/me").with(Auth.as("gestor.bahia", "UF:BA", "gestor")))
                .andExpect(jsonPath("$.data.scope.label").value("UF: BA"));
    }
}
