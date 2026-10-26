package io.github.emanuelluis.vaccine.alerts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Low-coverage alerts over the fixtures (goals: PENTA D3 95, ROTA D2 90; clock 2026-10-25, so the year
 * defaults to 2026). Salvador has expected population 100 for both doses: PENTA D3 53 doses (age 3
 * excluded) = 53.0%, ROTA D2 5 doses = 5.0%. Feira de Santana (4 doses) and Sao Luis (2) are
 * suppressed, Imperatriz has no population, Maranhao (8 of 8) meets its goal.
 */
@SpeedLayerSetup
class AlertsIT {

    private static final String PATH = "/api/v1/alerts/low-coverage";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    private ResultActions as(JwtRequestPostProcessor caller, String query) throws Exception {
        return mvc.perform(get(PATH + query).with(caller));
    }

    private ResultActions read(String query) throws Exception {
        return as(Auth.reader(), query);
    }

    @Test
    void statesBelowTheirGoalAreSortedByGapDescending() throws Exception {
        read("?level=state")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.layer").value("merged"))
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[0].level").value("state"))
                .andExpect(jsonPath("$.data[0].code").value("29"))
                .andExpect(jsonPath("$.data[0].abbr").value("BA"))
                .andExpect(jsonPath("$.data[0].vaccineCode").value("ROTA"))
                .andExpect(jsonPath("$.data[0].doseLabel").value("D2"))
                .andExpect(jsonPath("$.data[0].coveragePct").value(5.0))
                .andExpect(jsonPath("$.data[0].goalPct").value(90.0))
                .andExpect(jsonPath("$.data[0].gapPp").value(85.0))
                .andExpect(jsonPath("$.data[0].numerator").value(5))
                .andExpect(jsonPath("$.data[0].denominator").value(100.0))
                .andExpect(jsonPath("$.data[1].vaccineCode").value("PENTA"))
                .andExpect(jsonPath("$.data[1].coveragePct").value(40.7))
                .andExpect(jsonPath("$.data[1].gapPp").value(54.3))
                .andExpect(jsonPath("$.data[1].denominator").value(140.0));
        // Maranhao meets the goal and is not listed; the explicit year works the same
        read("?level=state&year=2026").andExpect(jsonPath("$.meta.count").value(2));
        read("?level=state&year=2025")
                .andExpect(jsonPath("$.meta.count").value(3))
                .andExpect(jsonPath("$.data[0].coveragePct").value(0.0));
    }

    @Test
    void municipalitiesExcludeSuppressedAndNoPopulationCells() throws Exception {
        read("?level=municipality&state=BA")
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1))
                .andExpect(jsonPath("$.data[0].code").value("2927408"))
                .andExpect(jsonPath("$.data[0].abbr").doesNotExist())
                .andExpect(jsonPath("$.data[0].vaccineCode").value("ROTA"))
                .andExpect(jsonPath("$.data[0].numerator").value(5))
                .andExpect(jsonPath("$.data[1].vaccineCode").value("PENTA"))
                .andExpect(jsonPath("$.data[1].coveragePct").value(53.0))
                .andExpect(jsonPath("$.data[1].gapPp").value(42.0));
        // Imperatriz (no population) and Sao Luis (2 doses) never appear
        read("?level=municipality&state=MA")
                .andExpect(jsonPath("$.meta.count").value(0))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1));
    }

    @Test
    void vaccineAndDoseFiltersNarrowTheScan() throws Exception {
        read("?level=state&vaccine=PENTA&dose=D3")
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].vaccineCode").value("PENTA"));
        read("?level=state&vaccine=ROTA")
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].doseLabel").value("D2"));
        // BCG has no indicator dose, so no goal: nothing to alert on
        read("?level=state&vaccine=BCG").andExpect(jsonPath("$.meta.count").value(0));
        read("?level=municipality&state=BA&region=MUN:2927408")
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[0].code").value("2927408"));
        read("?level=municipality&state=BA&region=UF:MA").andExpect(status().isBadRequest());
    }

    @Test
    void scopeLimitsTheAreasAndLevels() throws Exception {
        var bahia = Auth.as("vaccine-web", "UF:BA", "profissional");
        as(bahia, "?level=state").andExpect(jsonPath("$.meta.count").value(2));
        as(bahia, "?level=state").andExpect(jsonPath("$.data[0].abbr").value("BA"));
        as(bahia, "?level=municipality&state=MA").andExpect(status().isForbidden());
        var salvador = Auth.as("vaccine-web", "MUN:2927408", "profissional");
        as(salvador, "?level=state").andExpect(status().isForbidden());
        as(salvador, "?level=municipality&state=BA")
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[0].code").value("2927408"));
        as(salvador, "?level=municipality&state=BA&region=MUN:2910800").andExpect(status().isForbidden());
    }

    @Test
    void badRequestsAre400() throws Exception {
        read("").andExpect(status().isBadRequest());
        read("?level=municipality").andExpect(status().isBadRequest());
        read("?level=country").andExpect(status().isBadRequest());
        read("?level=state&year=1999").andExpect(status().isBadRequest());
    }
}
