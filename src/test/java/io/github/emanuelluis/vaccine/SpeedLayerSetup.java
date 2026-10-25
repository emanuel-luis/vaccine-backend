package io.github.emanuelluis.vaccine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Shared setup of the speed-layer ITs: ingest enabled, fixed clock (2026-10-25T15:00Z, which is
 * 12:00 on 2026-10-25 in America/Sao_Paulo), Postgres in a container. Identical annotations let
 * the ITs share one Spring context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "vaccine.ingest.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SpeedLayerSetup.FixedClockConfiguration.class})
public @interface SpeedLayerSetup {

    Instant NOW = Instant.parse("2026-10-25T15:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
