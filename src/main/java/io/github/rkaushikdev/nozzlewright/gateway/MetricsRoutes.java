package io.github.rkaushikdev.nozzlewright.gateway;

import io.github.rkaushikdev.nozzlewright.telemetry.Metrics;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /metrics}: the gateway's metrics in the text exposition format, for whatever is scraping
 * this.
 *
 * <p>Left out of the API document and open without a key, like the health page, because a scraper is
 * configured once by somebody who never reads the document. A gateway that has not been prepared has
 * nothing to say and says it with an empty page rather than an error, so a scrape during start up does
 * not page anybody.
 */
@RestController
@Hidden
public class MetricsRoutes {

    private final Gateway gateway;

    /** The metrics page of one gateway. */
    public MetricsRoutes(Gateway gateway) {
        this.gateway = gateway;
    }

    /** The exposition text; empty before the gateway is prepared. */
    @GetMapping("/metrics")
    public ResponseEntity<byte[]> metrics() {
        byte[] body = gateway.metrics().map(Metrics::render).orElse(new byte[0]);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, Metrics.CONTENT_TYPE)
                .body(body);
    }
}
