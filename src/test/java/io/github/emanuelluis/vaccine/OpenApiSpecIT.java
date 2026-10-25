package io.github.emanuelluis.vaccine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
}
