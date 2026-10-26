package io.github.emanuelluis.vaccine.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reference lists, breakdowns, map, health units and small-cell suppression over the fixtures. The
 * September 2026 rows of the fixture: Salvador (BA, 2927408) has two units with 62 and 3 doses,
 * Feira de Santana (2910800) 4 doses, Imperatriz (MA, 2105302) 6 doses but no population and
 * Sao Luis (2111300) 2 doses.
 */
@SpeedLayerSetup
class DashboardIT {

    private static final String SEP = "from=2026-09-01&to=2026-09-30";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    private ResultActions as(JwtRequestPostProcessor caller, String path) throws Exception {
        return mvc.perform(get("/api/v1" + path).with(caller));
    }

    private ResultActions read(String path) throws Exception {
        return as(Auth.reader(), path);
    }

    private static JwtRequestPostProcessor salvador() {
        return Auth.as("vaccine-web", "MUN:2927408", "profissional");
    }

    @Test
    void regionsListStatesAndMunicipalitiesInScope() throws Exception {
        read("/reference/regions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(3))
                .andExpect(jsonPath("$.data[0].code").value("29"))
                .andExpect(jsonPath("$.data[0].abbr").value("BA"))
                .andExpect(jsonPath("$.data[0].name").value("Bahia"))
                .andExpect(jsonPath("$.data[0].level").value("state"));
        read("/reference/regions?state=BA")
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.data[0].code").value("2910800"))
                .andExpect(jsonPath("$.data[0].abbr").doesNotExist())
                .andExpect(jsonPath("$.data[0].level").value("municipality"))
                .andExpect(jsonPath("$.data[1].name").value("Salvador"));
        as(salvador(), "/reference/regions?state=BA")
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].code").value("2927408"));
        as(salvador(), "/reference/regions").andExpect(jsonPath("$.meta.count").value(1));
        as(salvador(), "/reference/regions?state=MA").andExpect(status().isForbidden());
        as(Auth.as("vaccine-web", "UF:MA", "gestor"), "/reference/regions?state=BA")
                .andExpect(status().isForbidden());
    }

    @Test
    void vaccinesExcludeUnknownAndFlagIndicators() throws Exception {
        read("/reference/vaccines")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(6))
                .andExpect(jsonPath("$.data[0].vaccineCode").value("BCG"))
                .andExpect(jsonPath("$.data[3].vaccineCode").value("PENTA"))
                .andExpect(jsonPath("$.data[3].doseLabel").value("D3"))
                .andExpect(jsonPath("$.data[3].doseNumber").value(3))
                .andExpect(jsonPath("$.data[3].isCoverageIndicator").value(true))
                .andExpect(jsonPath("$.data[3].targetAgeYears").value(1))
                .andExpect(jsonPath("$.data[0].isCoverageIndicator").value(false));
    }

    @Test
    void byVaccineSuppressesSmallMunicipalityCellsOnly() throws Exception {
        read("/dashboard/doses/by-vaccine?" + SEP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.layer").value("merged"))
                .andExpect(jsonPath("$.meta.suppressedCells").value(0))
                .andExpect(jsonPath("$.data[0].vaccineCode").value("PENTA"))
                .andExpect(jsonPath("$.data[0].vaccineName").value("Pentavalente"))
                .andExpect(jsonPath("$.data[0].doseLabel").value("D3"))
                .andExpect(jsonPath("$.data[0].doses").value(72))
                .andExpect(jsonPath("$.data[1].doses").value(5))
                .andExpect(jsonPath("$.data[1].suppressed").value(false));
        // a state is never suppressed, even for a small count
        read("/dashboard/doses/by-vaccine?" + SEP + "&region=UF:BA&ageBand=3")
                .andExpect(jsonPath("$.data[0].doses").value(7));
        read("/dashboard/doses/by-vaccine?" + SEP + "&region=MUN:2910800")
                .andExpect(jsonPath("$.meta.suppressedCells").value(1))
                .andExpect(jsonPath("$.data[0].doses").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].suppressed").value(true));
        // exactly k = 5 is shown
        as(salvador(), "/dashboard/doses/by-vaccine?" + SEP)
                .andExpect(jsonPath("$.data[0].doses").value(60))
                .andExpect(jsonPath("$.data[1].doses").value(5))
                .andExpect(jsonPath("$.data[1].suppressed").value(false));
    }

    @Test
    void byAgeBandOrdersBandsAndSuppresses() throws Exception {
        read("/dashboard/doses/by-age-band?" + SEP + "&region=MUN:2927408&vaccine=PENTA&dose=D3")
                .andExpect(jsonPath("$.meta.count").value(3))
                .andExpect(jsonPath("$.data[0].ageBandYears").value(0))
                .andExpect(jsonPath("$.data[0].doses").value(43))
                .andExpect(jsonPath("$.data[2].ageBandYears").value(3))
                .andExpect(jsonPath("$.data[2].doses").value(7));
        read("/dashboard/doses/by-age-band?" + SEP + "&region=MUN:2111300")
                .andExpect(jsonPath("$.meta.suppressedCells").value(1))
                .andExpect(jsonPath("$.data[0].doses").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].suppressed").value(true));
    }

    @Test
    void stateCoverageSumsMunicipalDenominators() throws Exception {
        read("/dashboard/map?level=state&metric=coverage&year=2026&vaccine=PENTA&dose=D3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.layer").value("merged"))
                .andExpect(jsonPath("$.data[0].code").value("21"))
                .andExpect(jsonPath("$.data[0].abbr").value("MA"))
                .andExpect(jsonPath("$.data[0].numerator").value(8))
                .andExpect(jsonPath("$.data[0].denominator").value(8.0))
                .andExpect(jsonPath("$.data[0].value").value(100.0))
                .andExpect(jsonPath("$.data[1].code").value("29"))
                .andExpect(jsonPath("$.data[1].numerator").value(57))
                .andExpect(jsonPath("$.data[1].denominator").value(140.0))
                .andExpect(jsonPath("$.data[1].value").value(40.7))
                // no denominator at all: no coverage, but the zero count is shown
                .andExpect(jsonPath("$.data[2].code").value("35"))
                .andExpect(jsonPath("$.data[2].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[2].numerator").value(0))
                .andExpect(jsonPath("$.data[2].reason").value("no_population"))
                .andExpect(jsonPath("$.data[2].suppressed").value(false));
    }

    @Test
    void municipalityCoverageSuppressesTheNumeratorAndFlagsNoPopulation() throws Exception {
        read("/dashboard/map?level=municipality&state=BA&metric=coverage&year=2026&vaccine=PENTA&dose=D3")
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1))
                .andExpect(jsonPath("$.data[0].code").value("2910800"))
                .andExpect(jsonPath("$.data[0].name").value("Feira de Santana"))
                .andExpect(jsonPath("$.data[0].abbr").doesNotExist())
                .andExpect(jsonPath("$.data[0].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].numerator").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].suppressed").value(true))
                .andExpect(jsonPath("$.data[0].reason").value("small_cell"))
                // the age-3 dose is outside the indicator's target age
                .andExpect(jsonPath("$.data[1].code").value("2927408"))
                .andExpect(jsonPath("$.data[1].numerator").value(53))
                .andExpect(jsonPath("$.data[1].denominator").value(100.0))
                .andExpect(jsonPath("$.data[1].value").value(53.0))
                .andExpect(jsonPath("$.data[1].reason").value(org.hamcrest.Matchers.nullValue()));
        read("/dashboard/map?level=municipality&state=MA&metric=coverage&year=2026&vaccine=PENTA&dose=D3")
                .andExpect(jsonPath("$.data[0].code").value("2105302"))
                .andExpect(jsonPath("$.data[0].value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].numerator").value(6))
                .andExpect(jsonPath("$.data[0].reason").value("no_population"))
                .andExpect(jsonPath("$.data[1].suppressed").value(true));
    }

    @Test
    void dosesMapUsesThePeriodAndTheMergedLiveRows() throws Exception {
        read("/dashboard/map?level=municipality&state=BA&metric=doses&" + SEP)
                .andExpect(jsonPath("$.data[0].suppressed").value(true))
                .andExpect(jsonPath("$.data[1].value").value(65))
                .andExpect(jsonPath("$.data[1].numerator").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/api/v1/doses")
                        .with(Auth.ingest())
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DoseJson.json(DoseJson.valid(UUID.randomUUID()))))
                .andExpect(status().isCreated());
        read("/dashboard/map?level=state&metric=doses&from=2026-10-25&to=2026-10-25")
                .andExpect(jsonPath("$.data[0].code").value("21"))
                .andExpect(jsonPath("$.data[0].value").value(1))
                .andExpect(jsonPath("$.data[0].suppressed").value(false))
                .andExpect(jsonPath("$.data[1].value").value(0));
    }

    @Test
    void mapRespectsTheCallersScope() throws Exception {
        as(salvador(), "/dashboard/map?level=municipality&state=BA&metric=doses&" + SEP)
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].code").value("2927408"));
        as(salvador(), "/dashboard/map?level=state&metric=doses&" + SEP).andExpect(status().isForbidden());
        as(salvador(), "/dashboard/map?level=municipality&state=MA&metric=doses&" + SEP)
                .andExpect(status().isForbidden());
        var bahia = Auth.as("vaccine-web", "UF:BA", "profissional");
        as(bahia, "/dashboard/map?level=state&metric=doses&" + SEP)
                .andExpect(jsonPath("$.meta.count").value(1))
                .andExpect(jsonPath("$.data[0].abbr").value("BA"));
        as(bahia, "/dashboard/map?level=municipality&state=MA&metric=doses&" + SEP)
                .andExpect(status().isForbidden());
    }

    @Test
    void mapRejectsBadCoverageRequests() throws Exception {
        // BCG D1 is not a coverage indicator
        read("/dashboard/map?level=state&metric=coverage&vaccine=BCG&dose=D1").andExpect(status().isBadRequest());
        read("/dashboard/map?level=state&metric=coverage").andExpect(status().isBadRequest());
        read("/dashboard/map?level=municipality&metric=doses&" + SEP).andExpect(status().isBadRequest());
        read("/dashboard/map?level=state&metric=doses").andExpect(status().isBadRequest());
        read("/dashboard/map?level=country&metric=doses&" + SEP).andExpect(status().isBadRequest());
    }

    @Test
    void healthUnitsListActiveUnitsWithSuppressedCells() throws Exception {
        read("/dashboard/health-units?municipality=2927408&" + SEP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.count").value(2))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1))
                .andExpect(jsonPath("$.data[0].cnesCode").value("0000100"))
                .andExpect(jsonPath("$.data[0].unitName").value("UBS SALVADOR A"))
                .andExpect(jsonPath("$.data[0].latitude").value(-12.97))
                .andExpect(jsonPath("$.data[0].doses").value(62))
                .andExpect(jsonPath("$.data[1].cnesCode").value("0000101"))
                .andExpect(jsonPath("$.data[1].latitude").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[1].doses").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[1].suppressed").value(true));
        // zero is never suppressed
        read("/dashboard/health-units?municipality=2927408&vaccine=ROTA&" + SEP)
                .andExpect(jsonPath("$.data[0].doses").value(5))
                .andExpect(jsonPath("$.data[1].doses").value(0))
                .andExpect(jsonPath("$.data[1].suppressed").value(false));
        as(Auth.as("vaccine-web", "MUN:2111300", "profissional"), "/dashboard/health-units?municipality=2927408&" + SEP)
                .andExpect(status().isForbidden());
    }

    @Test
    void summaryAndTimeseriesSuppressAtMunicipalityLevel() throws Exception {
        read("/dashboard/summary?" + SEP + "&region=MUN:2111300")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.suppressedFields[0]").value("dosesInPeriod"))
                .andExpect(jsonPath("$.data.dosesToday").value(0))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1));
        read("/dashboard/summary?" + SEP + "&region=UF:MA")
                .andExpect(jsonPath("$.data.dosesInPeriod").value(8))
                .andExpect(jsonPath("$.data.suppressedFields").isEmpty())
                .andExpect(jsonPath("$.meta.suppressedCells").value(0));
        read("/dashboard/doses/timeseries?" + SEP + "&region=MUN:2111300")
                .andExpect(jsonPath("$.data[0].periodStart").value("2026-09-12"))
                .andExpect(jsonPath("$.data[0].doses").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].suppressed").value(true))
                .andExpect(jsonPath("$.meta.suppressedCells").value(1));
        // a municipality caller without `region` is a municipality region too
        as(Auth.as("vaccine-web", "MUN:2111300", "profissional"), "/dashboard/summary?" + SEP)
                .andExpect(jsonPath("$.data.suppressedFields[0]").value("dosesInPeriod"));
    }
}
