package io.github.rkaushikdev.nozzlewright.api;

import java.util.Map;

/**
 * The liveness answer.
 *
 * @param ok whether the gateway can do its job
 * @param version this build
 * @param siteId the site
 * @param printers machines with a database row
 * @param database {@code up} or {@code down}
 * @param keys how many API keys the gateway accepts; never which ones
 * @param openToAll whether the gateway answers anybody who asks
 * @param polling whether the poller is running
 * @param build the runtime and the database engine underneath, for a support call
 */
public record HealthOut(
        boolean ok,
        String version,
        String siteId,
        int printers,
        String database,
        int keys,
        boolean openToAll,
        boolean polling,
        Map<String, Object> build) {}
