package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Durations;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Posts notices as JSON to one URL.
 *
 * <p>The timeout is short on purpose. A webhook endpoint that takes four seconds to answer is an
 * endpoint that will eventually take forty, and the poll loop is not somewhere to find that out. The
 * post runs on its own daemon thread, {@code nozzlewright-webhook}, which is interrupted when the time
 * runs out.
 *
 * @param url where to post
 * @param client what posts it
 * @param token sent as a bearer token when not empty
 * @param timeoutSeconds how long to wait for an answer
 * @param name what the logs call this sink
 */
public record WebhookSink(String url, Poster client, String token, double timeoutSeconds, String name) implements Sink {

    /** A sink posting to {@code url} with no token, giving up after three seconds. */
    public WebhookSink(String url, Poster client) {
        this(url, client, "", 3.0, "webhook");
    }

    /** The same sink sending a bearer token. */
    public WebhookSink withToken(String value) {
        return new WebhookSink(url, client, value, timeoutSeconds, name);
    }

    /** The same sink with another timeout. */
    public WebhookSink withTimeoutSeconds(double value) {
        return new WebhookSink(url, client, token, value, name);
    }

    /**
     * Post one notice.
     *
     * @throws HttpTimeoutException when no answer came in time
     * @throws IOException when the post itself failed
     * @throws IllegalStateException when the endpoint answered with a status of 400 or above
     */
    @Override
    public void deliver(Notice notice) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        if (!token.isEmpty()) {
            headers.put("authorization", "Bearer " + token);
        }
        Map<String, Object> body = notice.asMap();
        FutureTask<Integer> post = new FutureTask<>(() -> client.post(url, body, headers));
        Thread.ofPlatform().daemon().name("nozzlewright-webhook").start(post);
        int code;
        try {
            code = post.get(Durations.ofSeconds(timeoutSeconds).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            post.cancel(true);
            throw new HttpTimeoutException(
                    "the webhook gave no answer inside " + Numbers.shortest(timeoutSeconds) + " seconds");
        } catch (InterruptedException stopped) {
            post.cancel(true);
            throw stopped;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IOException("the webhook post failed: " + cause, cause);
        }
        if (code >= 400) {
            throw new IllegalStateException("webhook answered " + code);
        }
    }
}
