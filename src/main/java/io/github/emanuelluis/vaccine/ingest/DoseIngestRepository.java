package io.github.emanuelluis.vaccine.ingest;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Writes of the speed layer: raw.live_dose, live.dose_daily, NOTIFY and ops.ingest_reject. */
@Repository
public class DoseIngestRepository {

    private static final Logger log = LoggerFactory.getLogger(DoseIngestRepository.class);

    /** A stored live dose; {@code requestSha256} is what a replay is compared against. */
    record Stored(UUID eventId, long generation, OffsetDateTime receivedAt, String requestSha256) {}

    /** {@code created} is false when the idempotency key already existed (then {@code stored} is that row). */
    record Outcome(Stored stored, boolean created) {}

    private final JdbcClient jdbc;

    DoseIngestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Stored> find(String clientId, String key) {
        return jdbc.sql("""
                        select event_id, generation, received_at, request_sha256
                        from raw.live_dose
                        where client_id = :client and idempotency_key = :key
                        """)
                .param("client", clientId)
                .param("key", key)
                .query(Stored.class)
                .optional();
    }

    /**
     * The generation protocol (docs/WEB_PLATFORM_PLAN.md, section 3.1): lock first, then read the
     * open generation, so a close of generation W waits for this transaction.
     */
    @Transactional
    public Outcome insert(String clientId, String key, String hash, DoseRequest r, DoseValidator.Validated v) {
        jdbc.sql("lock table raw.live_dose in row exclusive mode").update();
        long generation = jdbc.sql("select generation from ops.live_generation")
                .query(Long.class)
                .single();
        Optional<OffsetDateTime> receivedAt = jdbc.sql("""
                        insert into raw.live_dose (event_id, generation, client_id, idempotency_key, request_sha256,
                            person_id, birth_date, sex, cnes_code, vaccine_code, dose_label, lot_number,
                            administered_at)
                        values (:event, :generation, :client, :key, :hash, :person, :birth, :sex, :cnes, :vaccine,
                            :dose, :lot, :at)
                        on conflict (client_id, idempotency_key) do nothing
                        returning received_at
                        """)
                .param("event", UUID.randomUUID())
                .param("generation", generation)
                .param("client", clientId)
                .param("key", key)
                .param("hash", hash)
                .param("person", r.personId())
                .param("birth", r.birthDate())
                .param("sex", r.sex())
                .param("cnes", r.cnesCode())
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .param("lot", r.lotNumber())
                .param("at", r.administeredAt())
                .query(OffsetDateTime.class)
                .optional();
        if (receivedAt.isEmpty()) {
            return new Outcome(find(clientId, key).orElseThrow(), false);
        }
        Stored stored = find(clientId, key).orElseThrow();
        jdbc.sql("""
                        insert into live.dose_daily (generation, administered_on, cnes_code, vaccine_code,
                            dose_label, age_band_years, municipality_code, state_abbr, doses)
                        values (:generation, :day, :cnes, :vaccine, :dose, :band, :municipality, :state, 1)
                        on conflict (generation, administered_on, cnes_code, vaccine_code, dose_label, age_band_years)
                        do update set doses = live.dose_daily.doses + 1, updated_at = now()
                        """)
                .param("generation", generation)
                .param("day", v.administeredOn())
                .param("cnes", r.cnesCode())
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .param("band", v.ageBandYears())
                .param("municipality", v.municipalityCode())
                .param("state", v.stateAbbr())
                .update();
        jdbc.sql("""
                        select pg_notify('dose_event', json_build_object('state', cast(:state as text),
                            'municipality', cast(:municipality as integer), 'vaccine', cast(:vaccine as text),
                            'dose', cast(:dose as text), 'n', 1)::text)
                        """)
                .param("state", v.stateAbbr())
                .param("municipality", v.municipalityCode())
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .query()
                .listOfRows();
        return new Outcome(stored, true);
    }

    /** Runs outside any transaction (autocommit), so a reject survives the rollback of the failed request. */
    void reject(String clientId, String key, int status, String reason, String detail) {
        try {
            jdbc.sql("""
                            insert into ops.ingest_reject (client_id, idempotency_key, http_status, reason, detail)
                            values (:client, :key, :status, :reason, :detail)
                            """)
                    .param("client", clientId)
                    .param("key", key)
                    .param("status", status)
                    .param("reason", reason)
                    .param("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 300)))
                    .update();
        } catch (RuntimeException e) {
            log.warn("could not record the ingest reject ({} {})", status, reason, e);
        }
    }
}
