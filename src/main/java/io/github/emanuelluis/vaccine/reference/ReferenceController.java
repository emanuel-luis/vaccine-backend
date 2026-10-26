package io.github.emanuelluis.vaccine.reference;

import io.github.emanuelluis.vaccine.reference.ReferenceRepository.Region;
import io.github.emanuelluis.vaccine.reference.ReferenceRepository.Vaccine;
import io.github.emanuelluis.vaccine.region.RegionFilter;
import io.github.emanuelluis.vaccine.security.RegionScope;
import io.github.emanuelluis.vaccine.security.ScopeGuard;
import io.github.emanuelluis.vaccine.web.ApiResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Filter options: states and municipalities in the caller's scope, and the vaccine schedule. */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    static final String REGIONS = "marts.dim_municipality";
    static final String VACCINES = "marts.dim_vaccine";

    private final ReferenceRepository repository;

    public ReferenceController(ReferenceRepository repository) {
        this.repository = repository;
    }

    /**
     * States in scope, or with {@code state} the municipalities of that state in scope. A municipality
     * caller sees its own state and, inside it, its own municipality.
     */
    @GetMapping("/regions")
    public ApiResponse<Region> regions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String state) {
        if (state != null) {
            return ApiResponse.of(REGIONS, false, repository.municipalities(ScopeGuard.stateAreas(jwt, state)));
        }
        RegionScope scope = ScopeGuard.scope(jwt);
        return ApiResponse.of(REGIONS, false, repository.states(new RegionFilter(scope.state(), null)));
    }

    @GetMapping("/vaccines")
    public ApiResponse<Vaccine> vaccines() {
        return ApiResponse.of(VACCINES, false, repository.vaccines());
    }
}
