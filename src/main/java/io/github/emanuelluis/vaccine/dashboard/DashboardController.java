package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.Filters;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.TimeseriesPoint;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.web.ApiItem;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard reads over the merged (batch + live) doses. Measures come from synthetic events. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    static final int MAX_DAYS = 1096;
    static final ZoneId LOCAL_ZONE = ZoneId.of("America/Sao_Paulo");
    static final String VACCINE = "[A-Z0-9_]{2,20}";
    static final String DOSE = "[A-Za-z0-9_]{1,10}";

    /** Data of the summary endpoint. */
    public record SummaryData(
            long dosesInPeriod, long dosesToday, LocalDate today, long batchGeneration, long liveDosesPending) {}

    private final MergedDosesRepository repository;
    private final Clock clock;

    public DashboardController(MergedDosesRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public ApiItem<SummaryData> summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        checkRange(from, to);
        LocalDate today = LocalDate.now(clock.withZone(LOCAL_ZONE));
        var s = repository.summary(
                from, to, today, new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, ageBand));
        var data = new SummaryData(s.dosesInPeriod(), s.dosesToday(), today, s.batchGeneration(), s.liveDosesPending());
        return ApiItem.of(MergedDosesRepository.SOURCE, true, ApiResponse.LAYER_MERGED, s.batchGeneration(), data);
    }

    @GetMapping("/doses/timeseries")
    public ApiResponse<TimeseriesPoint> timeseries(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") @Pattern(regexp = "day|week|month") String granularity,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        checkRange(from, to);
        var series = repository.timeseries(
                from, to, granularity, new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, ageBand));
        return ApiResponse.merged(MergedDosesRepository.SOURCE, true, series.batchGeneration(), series.points());
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidRequestException("`from` (%s) must not be after `to` (%s)".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidRequestException("the range may cover at most %d days".formatted(MAX_DAYS));
        }
    }
}
