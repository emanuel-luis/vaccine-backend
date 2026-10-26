package io.github.emanuelluis.vaccine.admin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Response records of the data-management endpoints (schema ops); columns in docs/data-model.md. */
public final class AdminRows {

    private AdminRows() {}

    /** A queued job; {@code rows} and {@code rejects} sum {@code ops.load_run_table} of {@code runId}. */
    public record Job(
            UUID jobId,
            String kind,
            String status,
            String requestedBy,
            OffsetDateTime requestedAt,
            @Nullable OffsetDateTime startedAt,
            @Nullable OffsetDateTime finishedAt,
            @Nullable OffsetDateTime heartbeatAt,
            @Nullable UUID runId,
            @Nullable String dbtInvocationId,
            @Nullable String error,
            @Nullable Long rows,
            @Nullable Long rejects) {}

    public record JobLogLine(int seq, OffsetDateTime loggedAt, String level, String message) {}

    /** One manifest entry verified by a load-reference run. */
    public record Source(
            String sourceId,
            String path,
            @Nullable String tableName,
            String publisher,
            String sourceUrl,
            String licenseNote,
            String licenseStatus,
            String retrievedAt,
            String snapshotDate,
            long rows,
            String sha256,
            boolean verified) {}

    /** Sources of the latest succeeded load-reference run; no run yet means null ids and no sources. */
    public record Sources(@Nullable UUID runId, @Nullable OffsetDateTime loadedAt, List<Source> sources) {}

    public record DbtInvocation(
            String invocationId,
            @Nullable UUID jobId,
            String command,
            OffsetDateTime generatedAt,
            @Nullable BigDecimal elapsedS,
            String dbtVersion,
            boolean success,
            @Nullable Long batchGeneration) {}

    public record DbtNodeResult(
            String uniqueId,
            String resourceType,
            String name,
            String status,
            @Nullable Long failures,
            @Nullable BigDecimal executionTimeS,
            @Nullable String message) {}

    /** First mismatching key of a failed check; {@code diffKey} is the JSON text of the key. */
    public record Diff(int lineNo, String diffKey, long expected, long actual) {}

    public record Check(
            String checkName,
            @Nullable Long batchGeneration,
            long openGeneration,
            long keysCompared,
            long keysMismatched,
            long expectedTotal,
            long actualTotal,
            boolean passed,
            List<Diff> diffs) {}

    public record Reconciliation(
            UUID runId,
            String status,
            OffsetDateTime startedAt,
            @Nullable OffsetDateTime finishedAt,
            List<Check> checks) {}

    public record HourDoses(OffsetDateTime hour, long doses) {}

    public record RejectReason(String reason, int httpStatus, long count) {}

    public record Ingest(List<HourDoses> acceptedPerHour, List<RejectReason> rejectsByReason) {}
}
