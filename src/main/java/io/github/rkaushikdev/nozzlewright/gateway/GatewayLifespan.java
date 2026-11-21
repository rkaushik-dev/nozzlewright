package io.github.rkaushikdev.nozzlewright.gateway;

import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;

/**
 * Starts a gateway when the HTTP service starts and stops it when the service stops.
 *
 * <p>The web server owns the process, so the poller has to start when the service starts and stop
 * when it stops, and neither of them gets to call the other directly. The phase sits below the web
 * server's: the gateway is polling before the first request is answered, and it stops only after the
 * server has stopped taking requests.
 */
public final class GatewayLifespan implements SmartLifecycle {

    /** Below the web server's own start and stop. */
    public static final int PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 2048;

    private static final EventLog LOG = EventLog.get("nozzlewright.serve");

    private final Gateway gateway;

    /** Tie {@code gateway} to the service it is registered with. */
    public GatewayLifespan(Gateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void start() {
        gateway.start();
    }

    /** Stop the gateway and wait for it; an interrupt cuts the wait short and is passed on. */
    @Override
    public void stop() {
        try {
            gateway.stop();
        } catch (InterruptedException interrupted) {
            LOG.warning("interrupted while stopping the gateway");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return gateway.running();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
