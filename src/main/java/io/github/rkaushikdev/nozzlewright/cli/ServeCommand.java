package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.api.Api;
import io.github.rkaushikdev.nozzlewright.api.GatewayApplication;
import io.github.rkaushikdev.nozzlewright.gateway.Gateway;
import io.github.rkaushikdev.nozzlewright.gateway.GatewayLifespan;
import io.github.rkaushikdev.nozzlewright.gateway.MetricsRoutes;
import io.github.rkaushikdev.nozzlewright.runtime.Lifecycle;
import io.github.rkaushikdev.nozzlewright.runtime.Shutdown;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code nozzlewright serve}: run the gateway.
 *
 * <p>This is what the service unit runs. Everything it needs is on the command line or in the
 * environment, because a box in a cabinet has no interactive shell and a configuration file is one
 * more thing to get out of step with the site.
 *
 * <p>The gateway rides on the HTTP service: it starts when the service starts and stops when the
 * service stops, and a stop signal stops both inside the grace period. The line {@code serving <site>
 * on http://<host>:<port>} goes out once the service is answering, with the real port when the port
 * asked for was nought.
 */
@Command(
        name = "serve",
        description = {"Run the gateway.", "", "Serve the API and poll the site until stopped."})
final class ServeCommand extends Leaf {

    private static final EventLog LOG = EventLog.get("nozzlewright.serve");

    @Option(names = "--host", defaultValue = "0.0.0.0", description = "Address to listen on.")
    private String host;

    @Option(names = "--port", defaultValue = "8420", description = "Port to listen on.")
    private int port;

    @Option(names = "--poll-interval", defaultValue = "15.0", description = "Seconds between readings of a machine.")
    private double pollInterval;

    @Option(
            names = "--retention-days",
            defaultValue = "" + DbCommands.DEFAULT_RETENTION_DAYS,
            description = "How long readings are kept.")
    private int retentionDays;

    @Option(names = "--api-keys", defaultValue = "", description = "Comma separated API keys. Empty leaves it open.")
    private String apiKeys;

    /**
     * Assemble a gateway from the command line context.
     *
     * <p>Keys given on the command line replace the ones in the site file rather than adding to them,
     * so a person who has to start a gateway with one known key can do it without editing anything.
     */
    static Gateway buildGateway(Context context, double pollIntervalSeconds, int retentionDays, String apiKeys) {
        Set<String> keys = new TreeSet<>();
        for (String part : apiKeys.split(",", -1)) {
            if (!part.strip().isEmpty()) {
                keys.add(part.strip());
            }
        }
        if (keys.isEmpty()) {
            keys.addAll(context.site().apiKeys());
        }
        return Gateway.builder(context.settings())
                .stateDir(context.stateDir())
                .pollIntervalSeconds(pollIntervalSeconds)
                .retention(retention(context, retentionDays))
                .apiKeys(keys)
                .credentials(context.credentials().orElse(null))
                .simulate(context.simulate())
                .build();
    }

    /**
     * Start the HTTP service over a prepared gateway's API, the gateway starting and stopping with it
     * and its metrics page beside the API.
     */
    static ConfigurableApplicationContext attachLifespan(Gateway gateway, Api api, String... args) {
        return GatewayApplication.run(api, List.of(new GatewayLifespan(gateway), new MetricsRoutes(gateway)), args);
    }

    /**
     * The retention this run should use.
     *
     * <p>The default on the flag is fourteen days, which is also the default in the janitor, so a flag
     * left alone must not overwrite a site file that says something else. Anything other than the
     * default is somebody meaning it.
     */
    private static Retention retention(Context context, int retentionDays) {
        if (retentionDays != DbCommands.DEFAULT_RETENTION_DAYS) {
            return Retention.defaults().withSamples(Duration.ofDays(retentionDays));
        }
        return context.site().retention();
    }

    @Override
    public Integer call() throws InterruptedException {
        range("--port", port, 0, 65535);
        Context context = context();
        Gateway gateway = buildGateway(context, pollInterval, retentionDays, apiKeys);
        Api api = gateway.prepare();
        String site = gateway.settings().siteId();
        LOG.info("serving", "host", host, "port", port, "site", site);
        AtomicReference<ConfigurableApplicationContext> served = new AtomicReference<>();
        Lifecycle.runUntilStopped(
                () -> {
                    ConfigurableApplicationContext app;
                    try {
                        app = attachLifespan(
                                gateway,
                                api,
                                "--server.address=" + host,
                                "--server.port=" + port,
                                "--spring.main.register-shutdown-hook=false");
                    } catch (RuntimeException refused) {
                        gateway.stop();
                        throw Output.fail("the service did not start: " + refused.getMessage());
                    }
                    served.set(app);
                    int bound =
                            ((WebServerApplicationContext) app).getWebServer().getPort();
                    out().println("serving " + site + " on http://" + host + ":" + bound);
                    out().flush();
                },
                () -> {
                    ConfigurableApplicationContext app = served.getAndSet(null);
                    if (app != null) {
                        app.close();
                    }
                    gateway.stop();
                },
                new Shutdown());
        return Main.OK;
    }
}
