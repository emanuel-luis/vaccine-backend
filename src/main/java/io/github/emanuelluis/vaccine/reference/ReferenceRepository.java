package io.github.emanuelluis.vaccine.reference;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reference lists for the filters of the web app (no Unknown members). */
@Repository
@Transactional(readOnly = true)
public class ReferenceRepository {

    /** A state ({@code code} 2 digits, {@code abbr} set) or a municipality ({@code code} 7 digits). */
    public record Region(String code, @Nullable String abbr, String name, String level) {}

    public record Vaccine(
            String vaccineCode,
            String vaccineName,
            String doseLabel,
            int doseNumber,
            boolean isCoverageIndicator,
            @Nullable Integer targetAgeYears) {}

    private final JdbcClient jdbc;

    public ReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** States of the filter (all of them for the national filter). */
    public List<Region> states(RegionFilter scope) {
        return jdbc.sql("""
                        select lpad(cast(min(state_code) as text), 2, '0') as code,
                               cast(state_abbr as text) as abbr, min(state_name) as name, 'state' as level
                        from marts.dim_municipality
                        where municipality_code <> 0
                          and (cast(:state as text) is null or state_abbr = cast(:state as text))
                        group by state_abbr
                        order by name
                        """)
                .param("state", scope.stateAbbr())
                .query(Region.class)
                .list();
    }

    /** Municipalities of the filter's state (and only its municipality, when the filter has one). */
    public List<Region> municipalities(RegionFilter areas) {
        return jdbc.sql("""
                        select lpad(cast(municipality_code as text), 7, '0') as code,
                               cast(null as text) as abbr, municipality_name as name, 'municipality' as level
                        from marts.dim_municipality
                        where municipality_code <> 0
                          and state_abbr = cast(:state as text)
                          and (cast(:municipality as integer) is null
                               or municipality_code = cast(:municipality as integer))
                        order by name, code
                        """)
                .param("state", areas.stateAbbr())
                .param("municipality", areas.municipalityCode())
                .query(Region.class)
                .list();
    }

    public List<Vaccine> vaccines() {
        return jdbc.sql("""
                        select vaccine_code, vaccine_name, dose_label, dose_number, is_coverage_indicator,
                               coverage_target_age_years as target_age_years
                        from marts.dim_vaccine
                        where vaccine_code <> 'UNKNOWN'
                        order by vaccine_code, dose_number
                        """).query(Vaccine.class).list();
    }
}
