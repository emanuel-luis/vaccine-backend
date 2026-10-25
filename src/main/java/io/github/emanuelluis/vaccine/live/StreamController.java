package io.github.emanuelluis.vaccine.live;

import io.github.emanuelluis.vaccine.dashboard.MergedDosesRepository;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-sent events: {@code ready} on connect, then {@code doses} and {@code batch}. No replay. */
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

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String region) throws IOException {
        RegionFilter filter = RegionFilter.parse(region);
        var emitter = new SseEmitter(TIMEOUT_MS);
        emitter.send(SseEmitter.event()
                .name("ready")
                .data(Map.of(
                        "serverTime", clock.instant().toString(),
                        "batchGeneration", repository.batchGeneration(),
                        "region", filter.label())));
        hub.register(emitter, filter);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
