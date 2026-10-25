package io.github.emanuelluis.vaccine;

import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {

        Locale.setDefault(Locale.of("pt", "BR"));
        SpringApplication.run(Application.class, args);
    }
}
