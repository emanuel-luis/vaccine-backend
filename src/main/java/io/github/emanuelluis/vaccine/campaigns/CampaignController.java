package io.github.emanuelluis.vaccine.campaigns;

import io.github.emanuelluis.vaccine.campaigns.CampaignDetail.DailyPoint;
import io.github.emanuelluis.vaccine.campaigns.CampaignDetail.Progress;
import io.github.emanuelluis.vaccine.dashboard.DashboardController;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.DayDoses;
import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository.Filters;
import io.github.emanuelluis.vaccine.dashboard.Suppression;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.RegionScope;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.web.ApiItem;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.NotFoundException;
import io.github.emanuelluis.vaccine.web.PreconditionException;
import io.github.emanuelluis.vaccine.web.UnprocessableException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vaccination campaigns. Reads (profissional, gestor, data-admin) see the campaigns whose region lies
 * inside their scope; writes are gestor only and use optimistic locking: {@code ETag} is the quoted
 * {@code version}, {@code PUT} and {@code DELETE} need a matching {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {

    static final String PROGRESS_SOURCE = "app.campaign + marts.mart_doses_daily + live.dose_daily";

    private final CampaignRepository campaigns;
    private final MergedDosesRepository merged;
    private final Clock clock;

    public CampaignController(CampaignRepository campaigns, MergedDosesRepository merged, Clock clock) {
        this.campaigns = campaigns;
        this.merged = merged;
        this.clock = clock;
    }

    /** Campaigns inside the caller's scope; {@code active=true} keeps those running today. */
    @GetMapping
    public ApiResponse<Campaign> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Boolean active) {
        RegionScope scope = ScopeGuard.scope(jwt);
        LocalDate today = today();
        // ponytail: scope filter in Java; campaigns are few. Push into SQL if the table grows past thousands.
        List<Campaign> rows = campaigns.list().stream()
                .filter(c -> inScope(scope, c))
                .filter(c -> !Boolean.TRUE.equals(active)
                        || (!c.startsOn().isAfter(today) && !c.endsOn().isBefore(today)))
                .toList();
        return ApiResponse.of(CampaignRepository.SOURCE, false, rows);
    }

    @PostMapping
    public ResponseEntity<ApiItem<Campaign>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CampaignRequest request) {
        check(jwt, request);
        Campaign c = campaigns.insert(UUID.randomUUID(), request, createdBy(jwt));
        return ResponseEntity.created(URI.create("/api/v1/campaigns/" + c.campaignId()))
                .header(HttpHeaders.ETAG, ETags.format(c.version()))
                .body(item(c));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiItem<CampaignDetail>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Campaign c = visible(jwt, id);
        RegionFilter region = RegionScope.parse(c.regionScope()).orElseThrow().filter();
        LocalDate today = today();
        LocalDate end = c.endsOn().isBefore(today) ? c.endsOn() : today;
        List<DayDoses> days = end.isBefore(c.startsOn())
                ? List.of()
                : merged.dailyDoses(c.startsOn(), end, new Filters(region, c.vaccineCode(), c.doseLabel(), null));
        long generation =
                days.isEmpty() ? merged.batchGeneration() : days.getFirst().batchGeneration();
        Progress progress = progress(c, days, Suppression.fine(region));
        int hidden = (progress.suppressed() ? 1 : 0)
                + (int) progress.daily().stream().filter(DailyPoint::suppressed).count();
        var body = ApiItem.of(
                PROGRESS_SOURCE, true, ApiResponse.LAYER_MERGED, generation, hidden, CampaignDetail.of(c, progress));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.format(c.version()))
                .body(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiItem<Campaign>> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CampaignRequest request) {
        visible(jwt, id);
        int version = requireVersion(ifMatch);
        check(jwt, request);
        Campaign c = campaigns.update(id, version, request).orElseThrow(CampaignController::stale);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.format(c.version()))
                .body(item(c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        visible(jwt, id);
        if (!campaigns.delete(id, requireVersion(ifMatch))) {
            throw stale();
        }
        return ResponseEntity.noContent().build();
    }

    /** Body rules (422), region inside the caller's scope (403), known vaccine and dose (422). */
    private void check(Jwt jwt, CampaignRequest r) {
        CampaignRules.validate(r);
        ScopeGuard.filter(jwt, r.regionScope());
        if (!campaigns.vaccineExists(r.vaccineCode(), r.doseLabel())) {
            throw new UnprocessableException(
                    "unknown_vaccine_dose",
                    r.doseLabel() != null ? "doseLabel" : "vaccineCode",
                    "unknown vaccine and dose combination");
        }
    }

    /** The campaign, or 404 when it does not exist or its region is outside the caller's scope. */
    private Campaign visible(Jwt jwt, UUID id) {
        RegionScope scope = ScopeGuard.scope(jwt);
        return campaigns
                .find(id)
                .filter(c -> inScope(scope, c))
                .orElseThrow(() -> new NotFoundException("campaign " + id + " not found"));
    }

    private static boolean inScope(RegionScope scope, Campaign c) {
        return RegionScope.parse(c.regionScope())
                .map(r -> scope.contains(r.filter()))
                .orElse(false);
    }

    private static int requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionException(
                    HttpStatus.PRECONDITION_REQUIRED, "if_match_required", "send the ETag of the campaign in If-Match");
        }
        return ETags.parse(ifMatch);
    }

    private static PreconditionException stale() {
        return new PreconditionException(
                HttpStatus.PRECONDITION_FAILED,
                "version_mismatch",
                "the campaign was changed by someone else: reload it and retry");
    }

    /** Raw daily counts to progress: municipality regions hide counts of 1 to 4 (days without doses are omitted). */
    private static Progress progress(Campaign c, List<DayDoses> days, boolean fine) {
        long total = days.stream().mapToLong(DayDoses::doses).sum();
        boolean hide = Suppression.applies(total, fine);
        List<DailyPoint> daily = days.stream()
                .map(d -> {
                    boolean h = Suppression.applies(d.doses(), fine);
                    return new DailyPoint(d.day(), h ? null : d.doses(), h);
                })
                .toList();
        BigDecimal pct = BigDecimal.valueOf(total)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(c.targetDoses()), 1, RoundingMode.HALF_UP);
        return new Progress(hide ? null : total, hide ? null : pct, hide, daily);
    }

    private static ApiItem<Campaign> item(Campaign c) {
        return ApiItem.of(CampaignRepository.SOURCE, false, ApiResponse.LAYER_BATCH, null, c);
    }

    private static String createdBy(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DashboardController.LOCAL_ZONE));
    }
}
