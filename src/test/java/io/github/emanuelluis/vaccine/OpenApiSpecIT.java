package io.github.emanuelluis.vaccine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.github.emanuelluis.vaccine.live.LiveListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed {@code docs/api/openapi.json} must equal what springdoc serves. The web client is
 * generated from that file, so drift fails the build. Regenerate with {@code make openapi}
 * ({@code -Dopenapi.write=true}). No database is needed: Flyway is off and the LISTEN thread is mocked.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
class OpenApiSpecIT {

    static final Path SPEC = Path.of("docs", "api", "openapi.json");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LiveListener listener;

    @Test
    void committedSpecMatchesTheRunningApi() throws Exception {
        String served = mvc.perform(get("/v3/api-docs"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String actual = served.endsWith("\n") ? served : served + "\n";
        assertThat(actual).as("GET /v3/api-docs").startsWith("{");

        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(SPEC.getParent());
            Files.writeString(SPEC, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(SPEC)
                .as("%s is missing: run `make openapi` and commit it", SPEC)
                .exists();
        String committed = Files.readString(SPEC, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("docs/api/openapi.json is out of date with the controllers: run `make openapi` and commit it")
                .isEqualTo(committed);
    }

    @Test
    void requiredAndNullablePropertiesFollowTheJavaTypes() throws Exception {
        String schemas = "$.components.schemas.";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(
                        schemas + "SummaryData.required",
                        containsInAnyOrder("today", "batchGeneration", "suppressedFields")))
                .andExpect(jsonPath(schemas + "SummaryData.properties.*", hasSize(6)))
                .andExpect(jsonPath(schemas + "Meta.required", not(hasItem("batchGeneration"))))
                .andExpect(jsonPath(schemas + "Meta.required", hasItem("source")))
                .andExpect(jsonPath(schemas + "Meta.properties.batchGeneration.type", contains("integer", "null")))
                .andExpect(jsonPath(schemas + "Me.required", not(hasItem("scope"))))
                .andExpect(jsonPath(schemas + "Me.properties.scope.oneOf[1].type", is("null")))
                .andExpect(jsonPath(schemas + "DoseRequest.required", not(hasItem("lotNumber"))))
                .andExpect(jsonPath(schemas + "ApiItemMe.required", containsInAnyOrder("data", "meta")))
                .andExpect(jsonPath(schemas + "Campaign.required", not(hasItem("doseLabel"))))
                .andExpect(jsonPath(schemas + "Campaign.required", hasItem("version")))
                .andExpect(jsonPath(schemas + "Campaign.properties.doseLabel.type", contains("string", "null")))
                .andExpect(jsonPath(schemas + "CampaignRequest.required", not(hasItem("doseLabel"))))
                .andExpect(jsonPath(schemas + "CampaignRequest.required", hasItem("targetDoses")))
                .andExpect(jsonPath(schemas + "Progress.required", containsInAnyOrder("suppressed", "daily")))
                .andExpect(jsonPath(schemas + "LowCoverage.required", not(hasItem("abbr"))))
                .andExpect(jsonPath(schemas + "LowCoverage.required", hasItem("gapPp")))
                .andExpect(jsonPath(schemas + "Vaccine.required", not(hasItem("coverageGoalPct"))));
    }
}
