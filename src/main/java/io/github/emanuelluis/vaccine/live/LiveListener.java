package io.github.emanuelluis.vaccine.live;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * One dedicated JDBC connection (outside the Hikari pool) that LISTENs for dose, batch and job
 * events and hands them to the hub. Runs on a virtual thread; reconnects with backoff.
 */
@Component
public class LiveListener {

    static final String[] CHANNELS = {"dose_event", "batch_event", "job_event"};

    private static final Logger log = LoggerFactory.getLogger(LiveListener.class);
    private static final long POLL_MS = 500;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final JdbcConnectionDetails details;
    private final LiveEventHub hub;
    private volatile boolean running;
    private volatile boolean listening;
    private Thread thread;

    public LiveListener(JdbcConnectionDetails details, LiveEventHub hub) {
        this.details = details;
        this.hub = hub;
    }

    @PostConstruct
    void start() {
        running = true;
        thread = Thread.ofVirtual().name("live-listener").start(this::loop);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        thread.interrupt();
        thread.join(3_000);
    }

    /** True while the connection is open and every channel is LISTENed. */
    public boolean isListening() {
        return listening;
    }

    private void loop() {
        long backoff = 500;
        while (running) {
            try (Connection c =
                    DriverManager.getConnection(details.getJdbcUrl(), details.getUsername(), details.getPassword())) {
                PGConnection pg = c.unwrap(PGConnection.class);
                try (Statement s = c.createStatement()) {
                    for (String channel : CHANNELS) {
                        s.execute("LISTEN " + channel);
                    }
                }
                listening = true;
                backoff = 500;
                while (running) {
                    PGNotification[] notifications = pg.getNotifications((int) POLL_MS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            hub.onNotification(n.getName(), n.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("live listener lost its connection, retrying in {} ms: {}", backoff, e.toString());
                }
            } finally {
                listening = false;
            }
            if (running) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }
}
