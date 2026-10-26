package io.github.emanuelluis.vaccine.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.emanuelluis.vaccine.Auth;
import io.github.emanuelluis.vaccine.DoseJson;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import io.github.emanuelluis.vaccine.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Real HTTP: the stream sends {@code ready}, then a coalesced {@code doses} event after a POST. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({
    TestcontainersConfiguration.class,
    SpeedLayerSetup.FixedClockConfiguration.class,
    StreamIT.TokenDecoderConfiguration.class
})
class StreamIT {

    /** Bearer values are test tokens: {@code reader} and {@code ingest} (see {@link Auth#decoder}). */
    @TestConfiguration(proxyBeanMethods = false)
    static class TokenDecoderConfiguration {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return Auth.decoder(SpeedLayerSetup.NOW);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LiveListener listener;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    @Test
    void readyThenADosesEventWithinThreeSecondsOfAPost() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!listener.isListening() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(listener.isListening()).isTrue();

        HttpClient client = HttpClient.newHttpClient();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        var response = client.sendAsync(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stream"))
                                .header("Accept", "text/event-stream")
                                .header("Authorization", "Bearer reader")
                                .build(),
                        HttpResponse.BodyHandlers.ofLines())
                .thenApply(r -> {
                    Thread.ofVirtual().start(() -> r.body().forEach(lines::add));
                    return r;
                })
                .get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");

        assertThat(next(lines, "event:ready", 3)).isTrue();
        String ready = lines.poll(2, TimeUnit.SECONDS);
        assertThat(ready).startsWith("data:").contains("\"region\":\"BR\"").contains("\"batchGeneration\":0");

        var post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/doses"))
                .header("Authorization", "Bearer ingest")
                .header("Idempotency-Key", "stream-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DoseJson.json(DoseJson.valid(UUID.randomUUID()))))
                .timeout(Duration.ofSeconds(5))
                .build();
        assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(201);

        assertThat(next(lines, "event:doses", 3)).isTrue();
        String data = lines.poll(2, TimeUnit.SECONDS);
        assertThat(data)
                .contains("\"total\":1")
                .contains("\"byState\":{\"MA\":1}")
                .contains("\"byVaccine\":{\"PENTA\":1}")
                .contains("\"municipalities\":[2111300]");
    }

    private static boolean next(BlockingQueue<String> lines, String expected, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (expected.equals(line)) {
                return true;
            }
        }
        return false;
    }
}
