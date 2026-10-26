package io.github.emanuelluis.vaccine.dashboard;

import io.github.emanuelluis.vaccine.region.RegionFilter;

/**
 * Small-number suppression (decision 52): a count of 1 to {@code K - 1} is hidden when its
 * geography is a municipality or a health unit. National and state cells, and zeros, are never
 * hidden. Applied in the API only, never in the marts.
 */
public final class Suppression {

    /** Cells with fewer persons than this are suppressed (k-anonymity style threshold). */
    public static final int K = 5;

    public static final String SMALL_CELL = "small_cell";

    private Suppression() {}

    /** A response cell that may have been suppressed. */
    public interface Cell {
        boolean suppressed();
    }

    /** True when {@code count} must be hidden; {@code fine} is true for municipality or unit geography. */
    public static boolean applies(long count, boolean fine) {
        return fine && count >= 1 && count < K;
    }

    /** True when the effective region is a single municipality. */
    public static boolean fine(RegionFilter region) {
        return region.municipalityCode() != null;
    }
}
