package io.github.emanuelluis.vaccine.ingest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /api/v1/doses}: one dose given to one synthetic person. */
public record DoseRequest(
        @NotNull UUID personId,
        @NotNull LocalDate birthDate,
        @NotNull @Pattern(regexp = "[FM]") String sex,
        @NotNull @Pattern(regexp = "\\d{7}") String cnesCode,
        @NotNull @Pattern(regexp = "[A-Z0-9_]{2,20}") String vaccineCode,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_]{1,10}") String doseLabel,
        @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") @Nullable String lotNumber,
        @NotNull OffsetDateTime administeredAt) {}
