package io.github.emanuelluis.vaccine.live;

import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.security.SecurityConfig;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-sent events: {@code ready} on connect, then {@code doses}, {@code batch} and (data-admin only) {@code job}. No replay. */
@RestController
@RequestMapping("/api/v1/stream")
public class StreamController {

    static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final LiveEventHub hub;
    private final MergedDosesRepository repository;
    private final Clock clock;

    public StreamController(LiveEventHub hub, MergedDosesRepository repository, Clock clock) {
        this.hub = hub;
        this.repository = repository;
        this.clock = clock;
    }

    /** The stream ends when the token expires (the client reconnects with a renewed one), at most 30 minutes. */
    long timeoutMs(Jwt jwt) {
        if (jwt.getExpiresAt() == null) {
            return TIMEOUT_MS;
        }
        long untilExp = Duration.between(clock.instant(), jwt.getExpiresAt()).toMillis();
        return Math.max(1, Math.min(untilExp, TIMEOUT_MS));
    }

    private static boolean isDataAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains(SecurityConfig.DATA_ADMIN);
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String region) throws IOException {
        RegionFilter filter = ScopeGuard.filter(jwt, region);
        var emitter = new SseEmitter(timeoutMs(jwt));
        emitter.send(SseEmitter.event()
                .name("ready")
                .data(Map.of(
                        "serverTime", clock.instant().toString(),
                        "batchGeneration", repository.batchGeneration(),
                        "region", filter.label())));
        hub.register(emitter, filter, isDataAdmin(jwt));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
