package io.github.emanuelluis.vaccine.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** CSV exports over the dashboard fixtures: format, scope, role and suppression. */
@SpeedLayerSetup
class ExportIT {

    private static final String SEP = "from=2026-09-01&to=2026-09-30";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    private static JwtRequestPostProcessor gestor() {
        return Auth.as("vaccine-web", "BR", "gestor");
    }

    private String csv(String dataset, String query) throws Exception {
        MvcResult result = mvc.perform(
                        get("/api/v1/exports/" + dataset + "?" + query).with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                "Content-Disposition", "attachment; filename=\"" + dataset + "-2026-10-25.csv\""))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).startsWith(0xEF, 0xBB, 0xBF);
        return new String(body, StandardCharsets.UTF_8).substring(1);
    }

    @Test
    void dosesDailyIsOneRowPerUnitCellWithSuppressedCountsEmpty() throws Exception {
        assertThat(csv("doses-daily", SEP + "&region=UF:BA"))
                .isEqualTo(String.join(
                        "\r\n",
                        "date_day;state;municipality_code;cnes_code;vaccine_code;dose_label;age_band_years;doses;suppressed",
                        "2026-09-10;BA;2927408;0000100;PENTA;D3;0;40;false",
                        "2026-09-10;BA;2927408;0000100;PENTA;D3;1;10;false",
                        "2026-09-11;BA;2927408;0000100;PENTA;D3;3;7;false",
                        "2026-09-11;BA;2927408;0000101;PENTA;D3;0;;true",
                        "2026-09-12;BA;2910800;0000200;PENTA;D3;0;;true",
                        "2026-09-13;BA;2927408;0000100;ROTA;D2;0;5;false",
                        ""));
    }

    @Test
    void coverageMunicipalityUsesDecimalCommaAndSuppressesTheNumerator() throws Exception {
        assertThat(csv("coverage-municipality", "state=BA&vaccine=PENTA&dose=D3&year=2026"))
                .isEqualTo(String.join(
                        "\r\n",
                        "code;name;value;numerator;denominator;suppressed;reason",
                        "2910800;Feira de Santana;;;40,0;true;small_cell",
                        "2927408;Salvador;53,0;53;100,0;false;",
                        ""));
    }

    @Test
    void healthUnitsListCoordinatesAndSuppressedDoses() throws Exception {
        assertThat(csv("health-units", "municipality=2927408&" + SEP))
                .isEqualTo(String.join(
                        "\r\n",
                        "cnes_code;unit_name;latitude;longitude;doses;suppressed",
                        "0000100;UBS SALVADOR A;-12,970000;-38,500000;62;false",
                        "0000101;UBS SALVADOR B;;;;true",
                        ""));
    }

    @Test
    void onlyGestorsExportAndTheScopeStillApplies() throws Exception {
        String path = "/api/v1/exports/doses-daily?" + SEP;
        mvc.perform(get(path).with(Auth.as("vaccine-web", "BR", "profissional")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(Auth.as("vaccine-web", "BR", "data-admin"))).andExpect(status().isForbidden());
        mvc.perform(get(path + "&region=UF:BA").with(Auth.as("vaccine-web", "UF:MA", "gestor")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/exports/health-units?municipality=2927408&" + SEP)
                        .with(Auth.as("vaccine-web", "UF:MA", "gestor")))
                .andExpect(status().isForbidden());
        // a state gestor exports its own rows only
        String own = new String(
                mvc.perform(get(path).with(Auth.as("vaccine-web", "UF:MA", "gestor")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(own).contains(";MA;").doesNotContain(";BA;");
    }

    @Test
    void unknownDatasetAndBadParametersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/exports/individual-doses?" + SEP).with(gestor()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/exports/doses-daily").with(gestor())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exports/coverage-municipality?state=BA").with(gestor()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exports/coverage-municipality?state=BA&vaccine=BCG&dose=D1&year=2026")
                        .with(gestor()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exports/health-units?" + SEP).with(gestor())).andExpect(status().isBadRequest());
    }
}
