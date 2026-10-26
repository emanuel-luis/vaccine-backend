package io.github.emanuelluis.vaccine.exports;

import io.github.emanuelluis.vaccine.dashboard.DashboardController;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.Rows;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.Filters;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import io.github.emanuelluis.vaccine.web.NotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CSV exports (gestor only) of the datasets the dashboards show, with the same scope and suppression. */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");
    private static final ZoneId LOCAL_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String VACCINE = "[A-Z0-9_]{2,20}";
    private static final String DOSE = "[A-Za-z0-9_]{1,10}";

    private final MergedDosesRepository repository;
    private final Clock clock;
    private final int maxRows;

    public ExportController(
            MergedDosesRepository repository, Clock clock, @Value("${vaccine.exports.max-rows:200000}") int maxRows) {
        this.repository = repository;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    /**
     * {@code doses-daily} (from, to; region and filters optional), {@code coverage-municipality}
     * (state, vaccine, dose, year) or {@code health-units} (municipality, from, to; filters optional).
     */
    @GetMapping("/{dataset}")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String dataset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String state,
            @RequestParam(required = false) @Pattern(regexp = "\\d{7}") String municipality,
            @RequestParam(required = false) @Min(2015) @Max(2030) Integer year,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        LocalDate today = LocalDate.now(clock.withZone(LOCAL_ZONE));
        byte[] body = switch (dataset) {
            case "doses-daily" -> {
                range(from, to);
                var rows = repository.dosesDaily(
                        from, to, new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, ageBand), maxRows + 1);
                yield csv(
                        List.of(
                                "date_day",
                                "state",
                                "municipality_code",
                                "cnes_code",
                                "vaccine_code",
                                "dose_label",
                                "age_band_years",
                                "doses",
                                "suppressed"),
                        rows,
                        r -> Arrays.asList(
                                r.dateDay(),
                                r.stateAbbr(),
                                r.municipalityCode(),
                                r.cnesCode(),
                                r.vaccineCode(),
                                r.doseLabel(),
                                r.ageBandYears(),
                                r.doses(),
                                r.suppressed()));
            }
            case "coverage-municipality" -> {
                if (state == null || vaccine == null || dose == null || year == null) {
                    throw new InvalidRequestException("coverage-municipality needs state, vaccine, dose, year");
                }
                var rows = repository.map(DashboardController.mapRequest(
                        jwt, "municipality", state, "coverage", year, null, null, vaccine, dose, null, today));
                yield csv(
                        List.of("code", "name", "value", "numerator", "denominator", "suppressed", "reason"),
                        rows,
                        r -> Arrays.asList(
                                r.code(),
                                r.name(),
                                r.value(),
                                r.numerator(),
                                r.denominator(),
                                r.suppressed(),
                                r.reason()));
            }
            case "health-units" -> {
                if (municipality == null) {
                    throw new InvalidRequestException("health-units needs municipality");
                }
                range(from, to);
                var rows = repository.healthUnits(
                        from, to, new Filters(ScopeGuard.filter(jwt, "MUN:" + municipality), vaccine, dose, ageBand));
                yield csv(
                        List.of("cnes_code", "unit_name", "latitude", "longitude", "doses", "suppressed"),
                        rows,
                        r -> Arrays.asList(
                                r.cnesCode(), r.unitName(), r.latitude(), r.longitude(), r.doses(), r.suppressed()));
            }
            default -> throw new NotFoundException("no export dataset named " + dataset);
        };
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("%s-%s.csv".formatted(dataset, today))
                                .build()
                                .toString())
                .body(body);
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidRequestException("`from` and `to` are required");
        }
        DashboardController.checkRange(from, to);
    }

    private <T> byte[] csv(List<String> header, Rows<T> rows, Function<T, List<Object>> cells) {
        if (rows.rows().size() > maxRows) {
            throw new InvalidRequestException(
                    "the export exceeds %d rows: narrow the period or the region".formatted(maxRows));
        }
        return CsvWriter.write(header, rows.rows().stream().map(cells).toList());
    }
}
