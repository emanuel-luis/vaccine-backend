package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.dashboard.DashboardRows.AgeBandDoses;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.HealthUnitDoses;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.MapArea;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.Rows;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.VaccineDoses;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.Filters;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.MapRequest;
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
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard reads over the merged (batch + live) doses. Measures come from synthetic events. Counts
 * of 1 to 4 at municipality or unit granularity are suppressed ({@link Suppression}).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    static final int MAX_DAYS = 1096;
    static final ZoneId LOCAL_ZONE = ZoneId.of("America/Sao_Paulo");
    static final String VACCINE = "[A-Z0-9_]{2,20}";
    static final String DOSE = "[A-Za-z0-9_]{1,10}";

    /** Data of the summary endpoint; a suppressed count is null and named in {@code suppressedFields}. */
    public record SummaryData(
            @Nullable Long dosesInPeriod,
            @Nullable Long dosesToday,
            LocalDate today,
            long batchGeneration,
            @Nullable Long liveDosesPending,
            List<String> suppressedFields) {}

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
        LocalDate today = today();
        var filters = new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, ageBand);
        var s = repository.summary(from, to, today, filters);
        boolean fine = Suppression.fine(filters.region());
        var hidden = new ArrayList<String>();
        Long period = count(s.dosesInPeriod(), fine, "dosesInPeriod", hidden);
        Long todayDoses = count(s.dosesToday(), fine, "dosesToday", hidden);
        Long pending = count(s.liveDosesPending(), fine, "liveDosesPending", hidden);
        var data = new SummaryData(period, todayDoses, today, s.batchGeneration(), pending, hidden);
        return ApiItem.of(
                MergedDosesRepository.SOURCE, true, ApiResponse.LAYER_MERGED, s.batchGeneration(), hidden.size(), data);
    }

    private static Long count(long value, boolean fine, String field, List<String> hidden) {
        if (Suppression.applies(value, fine)) {
            hidden.add(field);
            return null;
        }
        return value;
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
        return merged(repository.timeseries(
                from, to, granularity, new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, ageBand)));
    }

    @GetMapping("/doses/by-vaccine")
    public ApiResponse<VaccineDoses> byVaccine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        checkRange(from, to);
        return merged(repository.byVaccine(from, to, new Filters(ScopeGuard.filter(jwt, region), null, null, ageBand)));
    }

    @GetMapping("/doses/by-age-band")
    public ApiResponse<AgeBandDoses> byAgeBand(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose) {
        checkRange(from, to);
        return merged(repository.byAgeBand(from, to, new Filters(ScopeGuard.filter(jwt, region), vaccine, dose, null)));
    }

    /** Coverage or doses per state, or per municipality of one state; areas outside the scope are not returned. */
    @GetMapping("/map")
    public ApiResponse<MapArea> map(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Pattern(regexp = "state|municipality") String level,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String state,
            @RequestParam @Pattern(regexp = "coverage|doses") String metric,
            @RequestParam(required = false) @Min(2015) @Max(2030) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        return merged(
                repository.map(mapRequest(jwt, level, state, metric, year, from, to, vaccine, dose, ageBand, today())));
    }

    /** Shared by the map endpoint and the coverage export; validates the metric-specific parameters. */
    public static MapRequest mapRequest(
            Jwt jwt,
            String level,
            String state,
            String metric,
            Integer year,
            LocalDate from,
            LocalDate to,
            String vaccine,
            String dose,
            Integer ageBand,
            LocalDate today) {
        boolean stateLevel = level.equals("state");
        boolean coverage = metric.equals("coverage");
        if (!stateLevel && state == null) {
            throw new InvalidRequestException("`state` is required for level=municipality");
        }
        var areas = stateLevel ? ScopeGuard.stateFilter(jwt, state) : ScopeGuard.stateAreas(jwt, state);
        if (coverage) {
            if (vaccine == null || dose == null) {
                throw new InvalidRequestException("coverage needs `vaccine` and `dose`");
            }
            if (ageBand != null) {
                throw new InvalidRequestException("`ageBand` applies to metric=doses only");
            }
            int y = year != null ? year : today.getYear();
            return new MapRequest(
                    stateLevel, true, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), areas, vaccine, dose, null);
        }
        if (from == null || to == null) {
            throw new InvalidRequestException("metric=doses needs `from` and `to`");
        }
        checkRange(from, to);
        return new MapRequest(stateLevel, false, from, to, areas, vaccine, dose, ageBand);
    }

    /** Active units of one municipality with their doses; 403 when the municipality is outside the scope. */
    @GetMapping("/health-units")
    public ApiResponse<HealthUnitDoses> healthUnits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Pattern(regexp = "\\d{7}") String municipality,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DOSE) String dose,
            @RequestParam(required = false) @Min(0) @Max(6) Integer ageBand) {
        checkRange(from, to);
        var region = ScopeGuard.filter(jwt, "MUN:" + municipality);
        return merged(repository.healthUnits(from, to, new Filters(region, vaccine, dose, ageBand)));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(LOCAL_ZONE));
    }

    static <T> ApiResponse<T> merged(Rows<T> r) {
        return ApiResponse.merged(MergedDosesRepository.SOURCE, true, r.batchGeneration(), r.rows(), r.suppressed());
    }

    public static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidRequestException("`from` (%s) must not be after `to` (%s)".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidRequestException("the range may cover at most %d days".formatted(MAX_DAYS));
        }
    }
}
