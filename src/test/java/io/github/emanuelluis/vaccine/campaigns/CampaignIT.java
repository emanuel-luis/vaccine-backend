package io.github.emanuelluis.vaccine.campaigns;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Campaign CRUD over Postgres: status codes (201/200/204/403/404/412/422/428), optimistic locking
 * with ETag and If-Match, scope, and progress from the merged doses. The September 2026 fixture
 * rows give PENTA D3: 50 doses on 09-10, 10 on 09-11 and 12 on 09-12 nationwide (Salvador: 50 and 10;
 * Sao Luis: 2 on 09-12). The fixed clock is 2026-10-25.
 */
@SpeedLayerSetup
class CampaignIT {

    private static final String BASE = "/api/v1/campaigns";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
        jdbc.sql("delete from app.campaign").update();
    }

    private static JwtRequestPostProcessor gestor(String scope) {
        return Auth.as("gestor.test", scope, "gestor");
    }

    private static String body(
            String name, String vaccine, String dose, String region, String from, String to, int target) {
        return """
                {"name":"%s","vaccineCode":"%s","doseLabel":%s,"regionScope":"%s","startsOn":"%s","endsOn":"%s","targetDoses":%d}""".formatted(name, vaccine, dose == null ? "null" : "\"" + dose + "\"", region, from, to, target);
    }

    private static String september(String region) {
        return body("Setembro", "PENTA", "D3", region, "2026-09-01", "2026-09-30", 200);
    }

    private ResultActions postBody(JwtRequestPostProcessor caller, String json) throws Exception {
        return mvc.perform(
                post(BASE).with(caller).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String create(JwtRequestPostProcessor caller, String json) throws Exception {
        String response = postBody(caller, json)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.data.campaignId");
    }

    private ResultActions putBody(String id, String ifMatch, String json) throws Exception {
        var request = put(BASE + "/" + id)
                .with(gestor("BR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    private ResultActions remove(String id, String ifMatch) throws Exception {
        var request = delete(BASE + "/" + id).with(gestor("BR"));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    @Test
    void createReturns201WithETagLocationAndTokenUser() throws Exception {
        var result = postBody(gestor("BR"), september("BR"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.name").value("Setembro"))
                .andExpect(jsonPath("$.data.doseLabel").value("D3"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.createdBy").value("gestor.test"))
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.campaignId");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo(BASE + "/" + id);
        // doseLabel null = every dose
        postBody(gestor("BR"), body("Todas", "PENTA", null, "UF:BA", "2026-09-01", "2026-09-30", 10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.doseLabel").value(nullValue()));
    }

    @Test
    void invalidValuesAre422WithCodeAndField() throws Exception {
        postBody(gestor("BR"), body("X", "PENTA", "D3", "BR", "2026-09-30", "2026-09-01", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ends_before_starts"))
                .andExpect(jsonPath("$.field").value("endsOn"));
        postBody(gestor("BR"), body("X", "PENTA", "D3", "BR", "2026-09-01", "2026-09-30", 0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_target_doses"))
                .andExpect(jsonPath("$.field").value("targetDoses"));
        postBody(gestor("BR"), body("X", "NOPE", "D3", "BR", "2026-09-01", "2026-09-30", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_vaccine_dose"));
        postBody(gestor("BR"), body("X", "PENTA", "D9", "BR", "2026-09-01", "2026-09-30", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown_vaccine_dose"))
                .andExpect(jsonPath("$.field").value("doseLabel"));
        postBody(gestor("BR"), body("   ", "PENTA", "D3", "BR", "2026-09-01", "2026-09-30", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_name"));
        postBody(gestor("BR"), body("X", "PENTA", "D3", "SP", "2026-09-01", "2026-09-30", 10))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_region_scope"));
        // a missing field is a plain 400
        postBody(gestor("BR"), "{\"name\":\"X\"}").andExpect(status().isBadRequest());
    }

    @Test
    void regionOutsideTheCallersScopeIs403() throws Exception {
        postBody(gestor("UF:BA"), september("UF:MA"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("region_out_of_scope"));
        postBody(gestor("UF:BA"), september("BR")).andExpect(status().isForbidden());
        postBody(gestor("UF:BA"), september("MUN:2927408")).andExpect(status().isCreated());
    }

    @Test
    void updateNeedsIfMatchAndBumpsTheVersion() throws Exception {
        String id = create(gestor("BR"), september("BR"));
        String changed = body("Outubro", "PENTA", "D3", "BR", "2026-10-01", "2026-12-31", 500);

        putBody(id, null, changed)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("if_match_required"));
        putBody(id, "\"7\"", changed)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version_mismatch"));
        putBody(id, "1", changed).andExpect(status().isPreconditionFailed());
        putBody(id, "\"1\"", changed)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.name").value("Outubro"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.createdBy").value("gestor.test"));
        // the first tag is stale now
        putBody(id, "\"1\"", changed).andExpect(status().isPreconditionFailed());
        putBody(id, "\"2\"", body("Outubro", "PENTA", "D3", "BR", "2026-10-01", "2026-12-31", 0))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(BASE + "/" + id).with(gestor("BR"))).andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void deleteNeedsIfMatch() throws Exception {
        String id = create(gestor("BR"), september("BR"));
        remove(id, null).andExpect(status().isPreconditionRequired());
        remove(id, "\"9\"").andExpect(status().isPreconditionFailed());
        remove(id, "\"1\"").andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/" + id).with(gestor("BR"))).andExpect(status().isNotFound());
        remove(id, "\"1\"").andExpect(status().isNotFound());
        putBody("00000000-0000-0000-0000-000000000009", "\"1\"", september("BR"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readersSeeOnlyCampaignsInsideTheirScope() throws Exception {
        String national = create(gestor("BR"), september("BR"));
        String bahia = create(gestor("BR"), body("BA", "PENTA", "D3", "UF:BA", "2026-09-01", "2026-09-30", 10));
        String salvador =
                create(gestor("BR"), body("SSA", "PENTA", "D3", "MUN:2927408", "2026-09-01", "2026-09-30", 10));
        var ba = Auth.as("vaccine-web", "UF:BA", "profissional");
        var ssa = Auth.as("vaccine-web", "MUN:2927408", "profissional");

        mvc.perform(get(BASE).with(gestor("BR")))
                .andExpect(jsonPath("$.meta.count").value(3));
        mvc.perform(get(BASE).with(ba))
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[*].name", org.hamcrest.Matchers.containsInAnyOrder("BA", "SSA")));
        mvc.perform(get(BASE).with(ssa))
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].name").value("SSA"));
        mvc.perform(get(BASE + "/" + national).with(ba)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + bahia).with(ssa)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + salvador).with(ba)).andExpect(status().isOk());
        // a state gestor cannot touch the national campaign either
        mvc.perform(delete(BASE + "/" + national).with(gestor("UF:BA")).header("If-Match", "\"1\""))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeFilterUsesTodayInSaoPaulo() throws Exception {
        create(gestor("BR"), september("BR"));
        create(gestor("BR"), body("Corrente", "PENTA", "D3", "BR", "2026-10-25", "2026-12-31", 10));
        create(gestor("BR"), body("Futura", "PENTA", "D3", "BR", "2026-10-26", "2026-12-31", 10));
        mvc.perform(get(BASE).with(gestor("BR")))
                .andExpect(jsonPath("$.meta.count").value(3));
        mvc.perform(get(BASE + "?active=true").with(gestor("BR")))
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Corrente"));
    }

    @Test
    void detailCarriesProgressFromTheMergedDoses() throws Exception {
        String id = create(gestor("BR"), september("BR"));
        mvc.perform(get(BASE + "/" + id).with(Auth.as("vaccine-web", "BR", "profissional")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.meta.layer").value("merged"))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(72))
                .andExpect(jsonPath("$.data.progress.pct").value(36.0))
                .andExpect(jsonPath("$.data.progress.suppressed").value(false))
                .andExpect(
                        jsonPath("$.data.progress.daily[*].date", contains("2026-09-10", "2026-09-11", "2026-09-12")))
                .andExpect(jsonPath("$.data.progress.daily[*].doses", contains(50, 10, 12)));
        // a dose label narrows the doses; this one has none
        String d1 = create(gestor("BR"), body("D1", "PENTA", "D1", "BR", "2026-09-01", "2026-09-30", 10));
        mvc.perform(get(BASE + "/" + d1).with(gestor("BR")))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(0))
                .andExpect(jsonPath("$.data.progress.pct").value(0.0))
                .andExpect(jsonPath("$.data.progress.daily").isEmpty());
        // not started yet: zero, no daily points
        String later = create(gestor("BR"), body("L", "PENTA", "D3", "BR", "2026-11-01", "2026-11-30", 10));
        mvc.perform(get(BASE + "/" + later).with(gestor("BR")))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(0))
                .andExpect(jsonPath("$.data.progress.daily").isEmpty());
    }

    @Test
    void municipalityProgressIsSuppressedWhenSmall() throws Exception {
        String saoLuis = create(gestor("BR"), september("MUN:2111300"));
        mvc.perform(get(BASE + "/" + saoLuis).with(gestor("BR")))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(nullValue()))
                .andExpect(jsonPath("$.data.progress.pct").value(nullValue()))
                .andExpect(jsonPath("$.data.progress.suppressed").value(true))
                .andExpect(jsonPath("$.data.progress.daily[0].doses").value(nullValue()))
                .andExpect(jsonPath("$.data.progress.daily[0].suppressed").value(true))
                .andExpect(jsonPath("$.meta.suppressedCells").value(2));
        String salvador = create(gestor("BR"), september("MUN:2927408"));
        mvc.perform(get(BASE + "/" + salvador).with(gestor("BR")))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(60))
                .andExpect(jsonPath("$.data.progress.suppressed").value(false))
                .andExpect(jsonPath("$.data.progress.daily[*].doses", contains(50, 10)));
        // a state is never suppressed, even for a small count
        String maranhao = create(gestor("BR"), body("MA", "ROTA", "D2", "UF:MA", "2026-09-01", "2026-09-30", 10));
        mvc.perform(get(BASE + "/" + maranhao).with(gestor("BR")))
                .andExpect(jsonPath("$.data.progress.dosesToDate").value(0))
                .andExpect(jsonPath("$.data.progress.suppressed").value(false));
    }
}
