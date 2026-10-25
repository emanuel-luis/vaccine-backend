package io.github.emanuelluis.vaccine;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Analytical API over the vaccination warehouse marts, plus the live dose speed layer. */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
