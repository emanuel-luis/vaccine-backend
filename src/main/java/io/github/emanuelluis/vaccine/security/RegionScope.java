package io.github.emanuelluis.vaccine.security;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Region a caller may read, parsed from the token claim {@code region_scope}: {@code BR},
 * {@code UF:BA} or {@code MUN:2927408}.
 *
 * @param level national, state or municipality
 * @param state state abbreviation (for a municipality, derived from the IBGE code), null for BR
 * @param municipalityCode 7-digit IBGE code, only at the municipality level
 */
public record RegionScope(Level level, String state, Integer municipalityCode) {

    public enum Level {
        BR,
        UF,
        MUN
    }

    public static final String CLAIM = "region_scope";
    public static final RegionScope NATIONAL = new RegionScope(Level.BR, null, null);

    private static final Pattern SYNTAX = Pattern.compile("BR|UF:([A-Z]{2})|MUN:([0-9]{7})");

    /** Empty when the claim is missing, malformed or names an unknown state. */
    public static Optional<RegionScope> parse(Object claim) {
        if (!(claim instanceof String text)) {
            return Optional.empty();
        }
        Matcher m = SYNTAX.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        if (m.group(1) != null) {
            return UfCodes.isUf(m.group(1))
                    ? Optional.of(new RegionScope(Level.UF, m.group(1), null))
                    : Optional.empty();
        }
        if (m.group(2) != null) {
            int code = Integer.parseInt(m.group(2));
            String state = UfCodes.abbrOfMunicipality(code);
            return state == null ? Optional.empty() : Optional.of(new RegionScope(Level.MUN, state, code));
        }
        return Optional.of(NATIONAL);
    }

    /** The filter that selects exactly this scope. */
    public RegionFilter filter() {
        return switch (level) {
            case BR -> RegionFilter.BR;
            case UF -> new RegionFilter(state, null);
            case MUN -> new RegionFilter(null, municipalityCode);
        };
    }

    /** True when every row selected by {@code requested} lies inside this scope. */
    public boolean contains(RegionFilter requested) {
        return switch (level) {
            case BR -> true;
            case UF ->
                state.equals(requested.stateAbbr())
                        || (requested.municipalityCode() != null
                                && state.equals(UfCodes.abbrOfMunicipality(requested.municipalityCode())));
            case MUN -> municipalityCode.equals(requested.municipalityCode());
        };
    }

    /** Portuguese label for the UI. */
    public String label() {
        return switch (level) {
            case BR -> "Brasil";
            case UF -> "UF: " + state;
            case MUN -> "Município: " + municipalityCode;
        };
    }
}
