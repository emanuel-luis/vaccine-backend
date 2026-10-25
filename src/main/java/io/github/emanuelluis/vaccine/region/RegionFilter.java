package io.github.emanuelluis.vaccine.region;

import io.github.emanuelluis.vaccine.web.InvalidRequestException;

/**
 * Geographic scope of a query: the whole country (both null), one state or one municipality. PR2
 * intersects the parsed filter with the caller's token scope.
 *
 * @param stateAbbr two-letter state, or null
 * @param municipalityCode 7-digit IBGE municipality code, or null
 */
public record RegionFilter(String stateAbbr, Integer municipalityCode) {

    public static final RegionFilter BR = new RegionFilter(null, null);

    /** Parses {@code BR} (default when null or blank), {@code UF:BA} or {@code MUN:2927408}. */
    public static RegionFilter parse(String region) {
        if (region == null || region.isBlank() || region.equals("BR")) {
            return BR;
        }
        if (region.matches("UF:[A-Z]{2}")) {
            return new RegionFilter(region.substring(3), null);
        }
        if (region.matches("MUN:[0-9]{7}")) {
            return new RegionFilter(null, Integer.valueOf(region.substring(4)));
        }
        throw new InvalidRequestException("`region` must be BR, UF:<UF> or MUN:<7-digit IBGE code>");
    }

    public boolean isNational() {
        return stateAbbr == null && municipalityCode == null;
    }

    public boolean covers(String state, int municipality) {
        return (stateAbbr == null || stateAbbr.equals(state))
                && (municipalityCode == null || municipalityCode == municipality);
    }

    /** The syntax {@link #parse} accepts. */
    public String label() {
        if (stateAbbr != null) {
            return "UF:" + stateAbbr;
        }
        return municipalityCode != null ? "MUN:" + municipalityCode : "BR";
    }
}
