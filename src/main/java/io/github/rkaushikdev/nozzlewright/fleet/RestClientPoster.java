package io.github.rkaushikdev.nozzlewright.fleet;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Posts webhook bodies over HTTP with a {@link RestClient}, the JSON written by its message converters.
 *
 * <p>A poster made with a timeout owns its HTTP client and its connections, and {@link #close} lets
 * them go; a poster that has been closed refuses to post. One made over an existing client leaves that
 * client to whoever made it.
 */
public final class RestClientPoster implements Poster, AutoCloseable {

    private final RestClient client;
    private final HttpClient owned;
    private volatile boolean closed;

    /** A poster on a client of its own that gives up connecting or reading after {@code timeout}. */
    public RestClientPoster(Duration timeout) {
        this.owned = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(owned);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** A poster over an existing client. */
    public RestClientPoster(RestClient client) {
        this.client = client;
        this.owned = null;
    }

    @Override
    public int post(String url, Map<String, Object> json, Map<String, String> headers) throws IOException {
        if (closed) {
            throw new IOException("webhook post to " + url + " refused: the client is closed");
        }
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

    /** True once closed. */
    public boolean closed() {
        return closed;
    }

    /**
     * Stop posting and let the connections go, waiting for a post already on the wire to finish.
     * Closing twice does nothing.
     */
    @Override
    public void close() {
        closed = true;
        if (owned != null) {
            owned.close();
        }
    }
}
