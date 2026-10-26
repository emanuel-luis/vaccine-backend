package io.github.emanuelluis.vaccine.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Envelope of every list endpoint: the rows plus where they come from and whether they are synthetic.
 *
 * @param data the rows
 * @param meta provenance of the rows
 */
public record ApiResponse<T>(List<T> data, Meta meta) {

    public static final String LAYER_BATCH = "batch";
    public static final String LAYER_MERGED = "merged";

    /**
     * @param source warehouse table the rows were read from
     * @param synthetic true when the measures come from the seeded generator, not real records
     * @param count number of rows in {@code data}
     * @param layer {@code batch} (marts only) or {@code merged} (marts plus the live aggregate)
     * @param batchGeneration generation the marts absorbed, null when it does not apply
     * @param suppressedCells cells hidden by small-number suppression (small-number suppression)
     */
    public record Meta(
            String source,
            boolean synthetic,
            int count,
            String layer,
            @Nullable Long batchGeneration,
            int suppressedCells) {}

    public static <T> ApiResponse<T> of(String source, boolean synthetic, List<T> data) {
        return new ApiResponse<>(data, new Meta(source, synthetic, data.size(), LAYER_BATCH, null, 0));
    }

    public static <T> ApiResponse<T> merged(String source, boolean synthetic, long batchGeneration, List<T> data) {
        return merged(source, synthetic, batchGeneration, data, 0);
    }

    public static <T> ApiResponse<T> merged(
            String source, boolean synthetic, long batchGeneration, List<T> data, int suppressedCells) {
        return new ApiResponse<>(
                data, new Meta(source, synthetic, data.size(), LAYER_MERGED, batchGeneration, suppressedCells));
    }
}
