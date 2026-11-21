package io.github.rkaushikdev.nozzlewright.runtime;

import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.util.concurrent.CountDownLatch;

/** A request to stop, and why it was made. Safe to request and wait on from any thread. */
public final class Shutdown {

    private static final EventLog LOG = EventLog.get("nozzlewright.runtime");

    private final CountDownLatch asked = new CountDownLatch(1);
    private volatile String reason = "";

    /** True once somebody has asked to stop. */
    public boolean requested() {
        return asked.getCount() == 0;
    }

    /** Why the stop was asked for; empty until it has been. */
    public String reason() {
        return reason;
    }

    /** Ask to stop for the usual reason. */
    public void request() {
        request("asked to stop");
    }

    /** Ask to stop. The first reason given is the one kept. */
    public synchronized void request(String why) {
        if (requested()) {
            return;
        }
        reason = why;
        asked.countDown();
        LOG.info("shutdown requested", "reason", why);
    }

    /** Wait until a stop is asked for, then say why. */
    public String await() throws InterruptedException {
        asked.await();
        return reason;
    }
}
