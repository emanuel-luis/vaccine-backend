package io.github.emanuelluis.vaccine.admin;

import io.github.emanuelluis.vaccine.admin.AdminRows.DbtInvocation;
import io.github.emanuelluis.vaccine.admin.AdminRows.DbtNodeResult;
import io.github.emanuelluis.vaccine.admin.AdminRows.Ingest;
import io.github.emanuelluis.vaccine.admin.AdminRows.Job;
import io.github.emanuelluis.vaccine.admin.AdminRows.JobLogLine;
import io.github.emanuelluis.vaccine.admin.AdminRows.Reconciliation;
import io.github.emanuelluis.vaccine.admin.AdminRows.Sources;
import io.github.emanuelluis.vaccine.web.ApiItem;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.ConflictException;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import io.github.emanuelluis.vaccine.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data management for the data-admin role (rules in SecurityConfig): sources, the job queue, dbt
 * results, reconciliation and ingest statistics. No region scope. The API only queues jobs; the
 * worker runs them.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    static final Set<String> KINDS = Set.of("load-reference", "dbt-build", "reconcile");
    static final Set<String> RESULT_STATUSES = Set.of("fail", "error", "warn", "pass", "success", "skipped");
    static final Set<String> RESOURCE_TYPES = Set.of("test", "model", "seed");

    /** Body of {@code POST /api/v1/admin/jobs}: {@code load-reference}, {@code dbt-build} or {@code reconcile}. */
    public record JobRequest(@NotNull String kind) {}

    private final AdminRepository repository;
    private final Clock clock;

    public AdminController(AdminRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/sources")
    public ApiItem<Sources> sources() {
        return item("ops.load_run_source", repository.sources());
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiItem<Job>> queue(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JobRequest request) {
        if (!KINDS.contains(request.kind())) {
            throw new InvalidRequestException("kind must be one of " + new TreeSet<>(KINDS));
        }
        Job job;
        try {
            job = repository.enqueue(UUID.randomUUID(), request.kind(), requestedBy(jwt));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("job_already_active", "a job is already queued or running");
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/admin/jobs/" + job.jobId()))
                .body(item("ops.job", job));
    }

    @GetMapping("/jobs")
    public ApiResponse<Job> jobs(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.of("ops.job", false, repository.jobs(limit));
    }

    @GetMapping("/jobs/{id}")
    public ApiItem<Job> job(@PathVariable UUID id) {
        return item("ops.job", repository.find(id).orElseThrow(() -> notFound(id)));
    }

    /** Log lines after {@code afterSeq}, oldest first, so a client resyncs with the last seq it saw. */
    @GetMapping("/jobs/{id}/log")
    public ApiResponse<JobLogLine> log(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int afterSeq,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit) {
        if (!repository.jobExists(id)) {
            throw notFound(id);
        }
        return ApiResponse.of("ops.job_log", false, repository.log(id, afterSeq, limit));
    }

    @GetMapping("/dbt/invocations")
    public ApiResponse<DbtInvocation> invocations(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.of("ops.dbt_invocation", false, repository.dbtInvocations(limit));
    }

    @GetMapping("/dbt/invocations/{id}/results")
    public ApiResponse<DbtNodeResult> results(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType) {
        if (status != null && !RESULT_STATUSES.contains(status)) {
            throw new InvalidRequestException("status must be one of " + new TreeSet<>(RESULT_STATUSES));
        }
        if (resourceType != null && !RESOURCE_TYPES.contains(resourceType)) {
            throw new InvalidRequestException("resourceType must be one of " + new TreeSet<>(RESOURCE_TYPES));
        }
        if (!repository.invocationExists(id)) {
            throw new NotFoundException("dbt invocation " + id + " not found");
        }
        return ApiResponse.of("ops.dbt_node_result", false, repository.dbtResults(id, status, resourceType));
    }

    @GetMapping("/reconciliation")
    public ApiResponse<Reconciliation> reconciliation(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        List<Reconciliation> runs = repository.reconciliation(limit);
        return ApiResponse.of("ops.reconciliation_check", false, runs);
    }

    @GetMapping("/ingest")
    public ApiItem<Ingest> ingest(@RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        OffsetDateTime since = clock.instant().minus(Duration.ofHours(hours)).atOffset(ZoneOffset.UTC);
        return item("raw.live_dose + ops.ingest_reject", repository.ingest(since));
    }

    private static <T> ApiItem<T> item(String source, T data) {
        return ApiItem.of(source, false, ApiResponse.LAYER_BATCH, null, data);
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("job " + id + " not found");
    }

    private static String requestedBy(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }
}
