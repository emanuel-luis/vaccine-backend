package io.github.emanuelluis.vaccine.live;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscribers of the SSE stream. Dose notifications are coalesced every 2 s per subscriber region
 * into one {@code doses} event; batch events are relayed; a {@code : ping} comment goes out every
 * 15 s. Subscribers whose emitter fails are dropped.
 */
@Component
public class LiveEventHub {

    static final int MAX_MUNICIPALITIES = 200;

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);

    /** Doses accumulated for one subscriber since its last flush. */
    private static final class Pending {
        long total;
        Instant from = Instant.now();
        final Map<String, Long> byState = new TreeMap<>();
        final Map<String, Long> byVaccine = new TreeMap<>();
        final Set<Integer> municipalities = new TreeSet<>();
    }

    private record Subscriber(SseEmitter emitter, RegionFilter region, Pending[] pending) {}

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong tick = new AtomicLong();
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-hub");
        t.setDaemon(true);
        return t;
    });

    public LiveEventHub(ObjectMapper mapper) {
        this.mapper = mapper;
        scheduler.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::ping, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    public void register(SseEmitter emitter, RegionFilter region) {
        var subscriber = new Subscriber(emitter, region, new Pending[] {new Pending()});
        subscribers.add(subscriber);
        Runnable remove = () -> subscribers.remove(subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
    }

    int subscriberCount() {
        return subscribers.size();
    }

    void onNotification(String channel, String payload) {
        try {
            switch (channel) {
                case "dose_event" -> onDose(mapper.readTree(payload));
                case "batch_event" -> relay("batch", mapper.readTree(payload));
                default -> log.debug("ignoring notification on {}", channel);
            }
        } catch (RuntimeException e) {
            log.warn("bad notification on {}: {}", channel, e.toString());
        }
    }

    private void onDose(JsonNode n) {
        String state = n.path("state").asString();
        int municipality = n.path("municipality").asInt();
        String vaccine = n.path("vaccine").asString();
        long count = n.path("n").asLong(1);
        for (Subscriber s : subscribers) {
            if (!s.region().covers(state, municipality)) {
                continue;
            }
            synchronized (s.pending()) {
                Pending p = s.pending()[0];
                p.total += count;
                p.byState.merge(state, count, Long::sum);
                p.byVaccine.merge(vaccine, count, Long::sum);
                p.municipalities.add(municipality);
            }
        }
    }

    private void relay(String event, JsonNode payload) {
        Map<String, Object> data = new HashMap<>();
        data.put("generation", payload.path("generation").asLong());
        data.put("invocationId", payload.path("invocationId").asString());
        for (Subscriber s : subscribers) {
            send(s, SseEmitter.event().name(event).data(data));
        }
    }

    void flush() {
        Instant now = Instant.now();
        for (Subscriber s : subscribers) {
            Pending p;
            synchronized (s.pending()) {
                p = s.pending()[0];
                if (p.total == 0) {
                    p.from = now;
                    continue;
                }
                s.pending()[0] = new Pending();
                s.pending()[0].from = now;
            }
            Map<String, Object> data = new TreeMap<>();
            data.put("tick", tick.incrementAndGet());
            data.put("from", p.from.toString());
            data.put("to", now.toString());
            data.put("total", p.total);
            if (s.region().isNational()) {
                data.put("byState", p.byState);
            }
            data.put("byVaccine", p.byVaccine);
            data.put(
                    "municipalities",
                    p.municipalities.stream().limit(MAX_MUNICIPALITIES).toList());
            send(s, SseEmitter.event().name("doses").data(data));
        }
    }

    void ping() {
        for (Subscriber s : subscribers) {
            send(s, SseEmitter.event().comment("ping"));
        }
    }

    private void send(Subscriber s, SseEmitter.SseEventBuilder event) {
        try {
            s.emitter().send(event);
        } catch (IOException | RuntimeException e) {
            subscribers.remove(s);
        }
    }
}
