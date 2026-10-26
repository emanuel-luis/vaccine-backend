package io.github.emanuelluis.vaccine.alerts;

import io.github.emanuelluis.vaccine.dashboard.DashboardController;
import io.github.emanuelluis.vaccine.dashboard.DashboardRows.LowCoverage;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.AlertRequest;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.security.UfCodes;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Areas below the coverage goal of an indicator dose (the goals come from {@code marts.dim_vaccine}). */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertsController {

    static final int LIMIT = 200;

    private final MergedDosesRepository repository;
    private final Clock clock;

    public AlertsController(MergedDosesRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Same scope rules as the map: state level is S>=UF, municipality level needs {@code state}. Without
     * {@code vaccine} and {@code dose} every indicator dose is checked; {@code region} narrows the areas.
     */
    @GetMapping("/low-coverage")
    public ApiResponse<LowCoverage> lowCoverage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Pattern(regexp = "state|municipality") String level,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String state,
            @RequestParam(required = false) @Min(2015) @Max(2030) Integer year,
            @RequestParam(required = false) @Pattern(regexp = DashboardController.VACCINE) String vaccine,
            @RequestParam(required = false) @Pattern(regexp = DashboardController.DOSE) String dose,
            @RequestParam(required = false) String region) {
        boolean stateLevel = level.equals("state");
        if (!stateLevel && state == null) {
            throw new InvalidRequestException("`state` is required for level=municipality");
        }
        RegionFilter areas = stateLevel ? ScopeGuard.stateFilter(jwt, state) : ScopeGuard.stateAreas(jwt, state);
        if (region != null && !region.isBlank()) {
            areas = narrow(areas, ScopeGuard.filter(jwt, region), stateLevel);
        }
        int y = year != null
                ? year
                : LocalDate.now(clock.withZone(DashboardController.LOCAL_ZONE)).getYear();
        var request =
                new AlertRequest(stateLevel, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), areas, vaccine, dose);
        var rows = repository.lowCoverage(request, LIMIT);
        return ApiResponse.merged(
                MergedDosesRepository.SOURCE, true, rows.batchGeneration(), rows.rows(), rows.suppressed());
    }

    /** {@code region} (already inside the scope) restricts the areas; one that contradicts them is a 400. */
    static RegionFilter narrow(RegionFilter areas, RegionFilter region, boolean stateLevel) {
        if (region.isNational()) {
            return areas;
        }
        if (stateLevel && region.municipalityCode() != null) {
            throw new InvalidRequestException("`region` must be BR or UF:<UF> at level=state");
        }
        String regionState = region.municipalityCode() != null
                ? UfCodes.abbrOfMunicipality(region.municipalityCode())
                : region.stateAbbr();
        if (areas.stateAbbr() != null && !areas.stateAbbr().equals(regionState)) {
            throw new InvalidRequestException("`region` lies outside the requested `state`");
        }
        return new RegionFilter(
                areas.stateAbbr() != null ? areas.stateAbbr() : regionState,
                region.municipalityCode() != null ? region.municipalityCode() : areas.municipalityCode());
    }
}
