package io.github.emanuelluis.vaccine.web;

import java.util.List;

/**
 * Envelope of every list endpoint: the rows plus where they come from and whether they are synthetic.
 *
 * @param data the rows
 * @param meta provenance of the rows
 */
public record ApiResponse<T>(List<T> data, Meta meta) {

    /**
     * @param source warehouse table the rows were read from
     * @param synthetic true when the measures come from the seeded generator, not real records
     * @param count number of rows in {@code data}
     */
    public record Meta(String source, boolean synthetic, int count) {}

    public static <T> ApiResponse<T> of(String source, boolean synthetic, List<T> data) {
        return new ApiResponse<>(data, new Meta(source, synthetic, data.size()));
    }
}
