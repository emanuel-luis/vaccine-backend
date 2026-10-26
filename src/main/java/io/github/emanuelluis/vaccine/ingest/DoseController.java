package io.github.emanuelluis.vaccine.ingest;

import io.github.emanuelluis.vaccine.ingest.DoseIngestRepository.Outcome;
import io.github.emanuelluis.vaccine.ingest.DoseIngestRepository.Stored;
import io.github.emanuelluis.vaccine.web.ApiItem;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.ConflictException;
import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import io.github.emanuelluis.vaccine.web.UnprocessableException;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live dose ingest. The bean exists only when {@code vaccine.ingest.enabled=true}: there is no
 * authentication before PR2, so the endpoint is 404 by default.
 */
@RestController
@RequestMapping("/api/v1/doses")
@ConditionalOnProperty(name = "vaccine.ingest.enabled", havingValue = "true")
public class DoseController {

    static final String CLIENT_ID = "anonymous";
    static final String SOURCE = "raw.live_dose";
    static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Response body of an accepted (or replayed) dose. */
    public record DoseAccepted(UUID eventId, long generation, OffsetDateTime receivedAt) {}

    private final DoseValidator validator;
    private final DoseIngestRepository repository;

    DoseController(DoseValidator validator, DoseIngestRepository repository) {
        this.validator = validator;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<ApiItem<DoseAccepted>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DoseRequest request) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRequestException("header Idempotency-Key is required: 1-64 chars of [A-Za-z0-9_-]");
        }
        String hash = RequestHash.sha256(request);
        var existing = repository.find(CLIENT_ID, key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        var validated = validator.validate(request);
        Outcome outcome;
        try {
            outcome = repository.insert(CLIENT_ID, key, hash, request, validated);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("ux_live_dose_person_dose")) {
                // A concurrent request with the same key can pass the lookup above and commit first:
                // ON CONFLICT only arbitrates the key index, so this insert trips the person-dose
                // index instead. Postgres raises it after the other transaction committed, so the
                // key is visible now and the request is a replay, not a second dose.
                var concurrent = repository.find(CLIENT_ID, key);
                if (concurrent.isPresent()) {
                    return replay(concurrent.get(), hash);
                }
                throw new ConflictException(
                        "dose_already_registered", "this person already has this vaccine dose registered");
            }
            throw e;
        }
        return outcome.created() ? body(HttpStatus.CREATED, outcome.stored()) : replay(outcome.stored(), hash);
    }

    private ResponseEntity<ApiItem<DoseAccepted>> replay(Stored stored, String hash) {
        if (!stored.requestSha256().equals(hash)) {
            throw new UnprocessableException(
                    "idempotency_key_reused", "Idempotency-Key", "the key was already used with another payload");
        }
        return body(HttpStatus.OK, stored);
    }

    private ResponseEntity<ApiItem<DoseAccepted>> body(HttpStatus status, Stored s) {
        var item = ApiItem.of(
                SOURCE,
                true,
                ApiResponse.LAYER_MERGED,
                null,
                new DoseAccepted(s.eventId(), s.generation(), s.receivedAt()));
        var builder = ResponseEntity.status(status);
        if (status == HttpStatus.OK) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(item);
    }
}
