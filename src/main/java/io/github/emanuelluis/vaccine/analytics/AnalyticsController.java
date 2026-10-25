package io.github.emanuelluis.vaccine.analytics;

import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.DosesByAgeBand;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.DosesByStateMonth;
import io.github.emanuelluis.vaccine.analytics.AnalyticsRows.StateCoverage;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Doses and coverage from the analytics marts. All measures come from synthetic events. */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    static final String STATE = "[A-Z]{2}";
    static final String VACCINE = "[A-Z0-9_]{2,20}";

    private final AnalyticsRepository repository;

    public AnalyticsController(AnalyticsRepository repository) {
        this.repository = repository;
    }

    /** Doses per state and month, by vaccine and dose. */
    @GetMapping("/doses/by-state-month")
    public ApiResponse<DosesByStateMonth> dosesByStateMonth(
            @RequestParam @Pattern(regexp = MonthRange.PATTERN) String from,
            @RequestParam @Pattern(regexp = MonthRange.PATTERN) String to,
            @RequestParam(required = false) @Pattern(regexp = STATE) String state,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine) {
        var rows = repository.dosesByStateMonth(MonthRange.parse(from, to), state, vaccine);
        return ApiResponse.of(AnalyticsRepository.DOSES_BY_STATE_MONTH, true, rows);
    }

    /** Doses per state and month, by vaccine, sex and completed-year age band at the dose. */
    @GetMapping("/doses/by-age-band")
    public ApiResponse<DosesByAgeBand> dosesByAgeBand(
            @RequestParam @Pattern(regexp = MonthRange.PATTERN) String from,
            @RequestParam @Pattern(regexp = MonthRange.PATTERN) String to,
            @RequestParam(required = false) @Pattern(regexp = STATE) String state,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine) {
        var rows = repository.dosesByAgeBand(MonthRange.parse(from, to), state, vaccine);
        return ApiResponse.of(AnalyticsRepository.DOSES_BY_AGE_BAND, true, rows);
    }

    /** Coverage of the indicator doses per state and year (synthetic numerator, census denominator). */
    @GetMapping("/coverage")
    public ApiResponse<StateCoverage> coverage(
            @RequestParam(required = false) @Min(2015) @Max(2030) Integer year,
            @RequestParam(required = false) @Pattern(regexp = STATE) String state,
            @RequestParam(required = false) @Pattern(regexp = VACCINE) String vaccine) {
        var rows = repository.coverage(year, state, vaccine);
        return ApiResponse.of(AnalyticsRepository.COVERAGE_BY_STATE, true, rows);
    }
}
