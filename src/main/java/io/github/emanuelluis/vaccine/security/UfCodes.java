package io.github.emanuelluis.vaccine.security;

import java.util.Map;

/** IBGE state codes: the first two digits of a municipality code. A static table, no database lookup. */
public final class UfCodes {

    private static final Map<Integer, String> BY_CODE = Map.ofEntries(
            Map.entry(11, "RO"),
            Map.entry(12, "AC"),
            Map.entry(13, "AM"),
            Map.entry(14, "RR"),
            Map.entry(15, "PA"),
            Map.entry(16, "AP"),
            Map.entry(17, "TO"),
            Map.entry(21, "MA"),
            Map.entry(22, "PI"),
            Map.entry(23, "CE"),
            Map.entry(24, "RN"),
            Map.entry(25, "PB"),
            Map.entry(26, "PE"),
            Map.entry(27, "AL"),
            Map.entry(28, "SE"),
            Map.entry(29, "BA"),
            Map.entry(31, "MG"),
            Map.entry(32, "ES"),
            Map.entry(33, "RJ"),
            Map.entry(35, "SP"),
            Map.entry(41, "PR"),
            Map.entry(42, "SC"),
            Map.entry(43, "RS"),
            Map.entry(50, "MS"),
            Map.entry(51, "MT"),
            Map.entry(52, "GO"),
            Map.entry(53, "DF"));

    private UfCodes() {}

    /** State abbreviation of a 7-digit municipality code, or null when the prefix is not a state. */
    public static String abbrOfMunicipality(int municipalityCode) {
        return BY_CODE.get(municipalityCode / 100_000);
    }

    static boolean isUf(String abbr) {
        return BY_CODE.containsValue(abbr);
    }
}
