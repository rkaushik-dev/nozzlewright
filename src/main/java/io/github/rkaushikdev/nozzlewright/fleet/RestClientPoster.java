package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Posts webhook bodies over HTTP with a {@link RestClient}, the JSON written by its message converters. */
public final class RestClientPoster implements Poster {

    private final RestClient client;

    /** A poster that gives up connecting or reading after {@code timeout}. */
    public RestClientPoster(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, Durations.waitMillis(timeout));
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** A poster over an existing client. */
    public RestClientPoster(RestClient client) {
        this.client = client;
    }

    @Override
    public int post(String url, Map<String, Object> json, Map<String, String> headers) throws IOException {
        try {
            Integer status = client.post()
                    .uri(url)
                    .headers(outgoing -> headers.forEach(outgoing::set))
                    .body(json)
                    .exchange((request, response) -> response.getStatusCode().value());
            return status == null ? 0 : status;
        } catch (RestClientException error) {
            throw new IOException("webhook post to " + url + " failed: " + error.getMessage(), error);
        }
    }
}
