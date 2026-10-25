package io.github.emanuelluis.vaccine.units;

import io.github.emanuelluis.vaccine.web.ApiResponse;
import io.github.emanuelluis.vaccine.web.NotFoundException;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** History of one health unit (type 2 slowly changing dimension built from dated CNES extracts). */
@RestController
@RequestMapping("/api/v1/health-units")
public class HealthUnitController {

    static final String SOURCE = "marts.dim_health_unit";

    /** One version of a unit; {@code validTo} is exclusive and 9999-12-31 for the current one. */
    public record HealthUnitVersion(
            String cnesCode,
            String unitName,
            String neighborhood,
            String street,
            Integer unitTypeCode,
            String unitTypeLabel,
            boolean isActive,
            int municipalityCode,
            String municipalityName,
            String stateAbbr,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDate validFrom,
            LocalDate validTo,
            boolean isCurrent) {}

    private final JdbcClient jdbc;

    public HealthUnitController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{cnesCode}/history")
    @Transactional(readOnly = true)
    public ApiResponse<HealthUnitVersion> history(@PathVariable @Pattern(regexp = "\\d{7}") String cnesCode) {
        List<HealthUnitVersion> versions = jdbc.sql("""
                        select u.cnes_code, u.unit_name, u.neighborhood, u.street, u.unit_type_code,
                               u.unit_type_label, u.is_active, u.municipality_code,
                               m.municipality_name, m.state_abbr, u.latitude, u.longitude,
                               u.valid_from, u.valid_to, u.is_current
                        from marts.dim_health_unit u
                        join marts.dim_municipality m on m.municipality_code = u.municipality_code
                        where u.cnes_code = :cnes_code
                        order by u.valid_from
                        """)
                .param("cnes_code", cnesCode)
                .query(HealthUnitVersion.class)
                .list();
        if (versions.isEmpty()) {
            throw new NotFoundException("no health unit with CNES code " + cnesCode);
        }
        return ApiResponse.of(SOURCE, false, versions);
    }
}
