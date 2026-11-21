package io.github.rkaushikdev.nozzlewright.fleet;

import java.io.IOException;
import java.util.Map;

/**
 * The one HTTP call the webhook sink needs, so a test can hand in a stub.
 *
 * <p>{@link RestClientPoster} is the real one.
 */
@FunctionalInterface
public interface Poster {

    /**
     * Post {@code json} to {@code url} with these headers.
     *
     * @return the response status code
     * @throws IOException when there was no response
     */
    int post(String url, Map<String, Object> json, Map<String, String> headers)
            throws IOException, InterruptedException;
}
