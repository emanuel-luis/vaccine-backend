package io.github.emanuelluis.vaccine.ingest;

import io.github.emanuelluis.vaccine.web.UnprocessableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules of a live dose, checked against the marts and the live rows before the write
 * transaction. A missing mart surfaces as a BadSqlGrammarException, answered with 503.
 */
@Component
@Transactional(readOnly = true)
class DoseValidator {

    /** Same rule as dbt's {@code administered_on}. */
    static final ZoneId LOCAL_ZONE = ZoneId.of("America/Sao_Paulo");

    static final Duration MAX_AHEAD = Duration.ofMinutes(5);
    static final Duration MAX_AGE = Duration.ofDays(30);
    static final int MAX_AGE_BAND = 6;

    /** What the write needs from a validated dose. */
    record Validated(LocalDate administeredOn, int ageBandYears, int municipalityCode, String stateAbbr) {}

    private record VaccineRow(int doseNumber, Integer minAgeDays, Integer maxAgeDays) {}

    private record UnitRow(boolean isActive, Integer municipalityCode, String stateAbbr) {}

    private record LotRow(String vaccineCode, LocalDate expiresOn) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    DoseValidator(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Validated validate(DoseRequest r) {
        Instant at = r.administeredAt().toInstant();
        Instant now = clock.instant();
        if (at.isAfter(now.plus(MAX_AHEAD))) {
            throw new UnprocessableException(
                    "administered_in_future", "administeredAt", "administeredAt is more than 5 minutes ahead");
        }
        if (at.isBefore(now.minus(MAX_AGE))) {
            throw new UnprocessableException(
                    "administered_too_old", "administeredAt", "administeredAt is older than 30 days");
        }
        LocalDate day = at.atZone(LOCAL_ZONE).toLocalDate();

        VaccineRow vaccine = jdbc.sql("""
                        select dose_number, min_age_days, max_age_days
                        from marts.dim_vaccine
                        where vaccine_code = :vaccine and dose_label = :dose
                        """)
                .param("vaccine", r.vaccineCode())
                .param("dose", r.doseLabel())
                .query(VaccineRow.class)
                .optional()
                .orElseThrow(() -> new UnprocessableException(
                        "unknown_vaccine_dose", "doseLabel", "unknown vaccine and dose combination"));

        long ageDays = ChronoUnit.DAYS.between(r.birthDate(), day);
        if (ageDays < 0
                || (vaccine.minAgeDays() != null && ageDays < vaccine.minAgeDays())
                || (vaccine.maxAgeDays() != null && ageDays > vaccine.maxAgeDays())) {
            throw new UnprocessableException(
                    "age_out_of_range", "birthDate", "age at the dose is outside the allowed window for this dose");
        }
        int ageBand = Period.between(r.birthDate(), day).getYears();
        if (ageBand > MAX_AGE_BAND) {
            throw new UnprocessableException(
                    "age_band_out_of_range", "birthDate", "age at the dose is above the 0-6 year age bands");
        }

        UnitRow unit = jdbc.sql("""
                        select u.is_active, m.municipality_code, m.state_abbr
                        from marts.dim_health_unit u
                        left join marts.dim_municipality m on m.municipality_code = u.municipality_code
                        where u.cnes_code = :cnes and u.valid_from <= :day and :day < u.valid_to
                        """)
                .param("cnes", r.cnesCode())
                .param("day", day)
                .query(UnitRow.class)
                .optional()
                .orElseThrow(() -> new UnprocessableException(
                        "unknown_unit", "cnesCode", "no health unit version is valid on the dose date"));
        if (!unit.isActive()) {
            throw new UnprocessableException(
                    "unit_inactive", "cnesCode", "the health unit is inactive on the dose date");
        }
        if (unit.municipalityCode() == null || unit.municipalityCode() == 0) {
            throw new UnprocessableException(
                    "unit_municipality_unknown",
                    "cnesCode",
                    "the municipality of the unit is not a known IBGE municipality");
        }

        if (r.lotNumber() != null) {
            var lots = jdbc.sql("select vaccine_code, expires_on from marts.dim_vaccine_lot where lot_number = :lot")
                    .param("lot", r.lotNumber())
                    .query(LotRow.class)
                    .list();
            if (lots.isEmpty()) {
                throw new UnprocessableException("unknown_lot", "lotNumber", "unknown lot");
            }
            LotRow lot = lots.stream()
                    .filter(l -> l.vaccineCode().equals(r.vaccineCode()))
                    .findFirst()
                    .orElseThrow(() -> new UnprocessableException(
                            "lot_vaccine_mismatch", "lotNumber", "the lot belongs to another vaccine"));
            if (lot.expiresOn() != null && lot.expiresOn().isBefore(day)) {
                throw new UnprocessableException("lot_expired", "lotNumber", "the lot was expired on the dose date");
            }
        }

        boolean outOfOrder = jdbc.sql("""
                        select exists (
                            select 1
                            from raw.live_dose l
                            join marts.dim_vaccine v
                              on v.vaccine_code = l.vaccine_code and v.dose_label = l.dose_label
                            where l.person_id = :person and l.vaccine_code = :vaccine
                              and ((v.dose_number < :n and l.administered_at >= :at)
                                or (v.dose_number > :n and l.administered_at <= :at)))
                        """)
                .param("person", r.personId())
                .param("vaccine", r.vaccineCode())
                .param("n", vaccine.doseNumber())
                .param("at", r.administeredAt())
                .query(Boolean.class)
                .single();
        if (outOfOrder) {
            throw new UnprocessableException(
                    "series_order_violation", "administeredAt", "doses of the same series must be given in dose order");
        }
        return new Validated(day, ageBand, unit.municipalityCode(), unit.stateAbbr());
    }
}
