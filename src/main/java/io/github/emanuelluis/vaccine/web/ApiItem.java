package io.github.emanuelluis.vaccine.web;

/**
 * Envelope of every single-object endpoint.
 *
 * @param data the object
 * @param meta provenance of the object ({@code count} is 1)
 */
public record ApiItem<T>(T data, ApiResponse.Meta meta) {

    public static <T> ApiItem<T> of(String source, boolean synthetic, String layer, Long batchGeneration, T data) {
        return new ApiItem<>(data, new ApiResponse.Meta(source, synthetic, 1, layer, batchGeneration, 0));
    }
}
