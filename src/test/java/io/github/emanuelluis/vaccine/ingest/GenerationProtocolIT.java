package io.github.emanuelluis.vaccine.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.emanuelluis.vaccine.DoseJson;
import io.github.emanuelluis.vaccine.SpeedLayerSetup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

/** The generation protocol of docs/WEB_PLATFORM_PLAN.md, section 3.1, under concurrent ingest and closes. */
@SpeedLayerSetup
class GenerationProtocolIT {

    private static final int WRITERS = 8;
    private static final int DOSES_PER_WRITER = 25;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void loadFixtures() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/marts.sql")).execute(dataSource);
    }

    private record Closed(long generation, long rows) {}

    private long rowsUpTo(long generation) {
        return jdbc.queryForObject("select count(*) from raw.live_dose where generation <= ?", Long.class, generation);
    }

    @Test
    void aClosedGenerationNeverChangesAndTheAggregateMatchesTheRows() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 1);
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Future<Integer>> writers = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            writers.add(pool.submit((Callable<Integer>) () -> {
                int created = 0;
                for (int i = 0; i < DOSES_PER_WRITER; i++) {
                    Map<String, Object> body = DoseJson.valid(UUID.randomUUID());
                    int status = mvc.perform(post("/api/v1/doses")
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(DoseJson.json(body)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 201) {
                        created++;
                    }
                }
                return created;
            }));
        }
        Future<List<Closed>> closer = pool.submit(() -> {
            List<Closed> closes = new ArrayList<>();
            while (writing.get() || closes.size() < 3) {
                long w = jdbc.queryForObject("select ops.close_live_generation('test')", Long.class);
                closes.add(new Closed(w, rowsUpTo(w)));
                for (Closed c : closes) {
                    assertThat(rowsUpTo(c.generation())).isEqualTo(c.rows());
                }
                Thread.sleep(15);
            }
            return closes;
        });

        int created = 0;
        for (Future<Integer> f : writers) {
            created += f.get();
        }
        writing.set(false);
        List<Closed> closes = closer.get();
        pool.shutdown();

        assertThat(created).isEqualTo(WRITERS * DOSES_PER_WRITER);
        assertThat(closes.size()).isGreaterThanOrEqualTo(3);
        for (Closed c : closes) {
            assertThat(rowsUpTo(c.generation())).isEqualTo(c.rows());
        }
        var perGeneration = jdbc.queryForList("""
                select r.generation, r.n, coalesce(d.s, 0) as s
                from (select generation, count(*) as n from raw.live_dose group by generation) r
                left join (select generation, sum(doses) as s from live.dose_daily group by generation) d
                  on d.generation = r.generation
                """);
        assertThat(perGeneration).isNotEmpty();
        for (var row : perGeneration) {
            assertThat(((Number) row.get("s")).longValue())
                    .as("generation %s", row.get("generation"))
                    .isEqualTo(((Number) row.get("n")).longValue());
        }
        assertThat(jdbc.queryForObject("select coalesce(sum(doses), 0) from live.dose_daily", Long.class))
                .isEqualTo((long) created);
    }
}
