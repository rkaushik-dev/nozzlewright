package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.settings.LinkSettings;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.transport.DialOutcome;
import io.github.rkaushikdev.nozzlewright.transport.Dialer;
import io.github.rkaushikdev.nozzlewright.transport.Transport;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One supervised conversation with one machine.
 *
 * <p>Everything below this class handles a single attempt at something: the channel sends one
 * command, the dialer opens one link, the authenticator opens one session. This is where those
 * become a thing that survives a shift. A supervisor thread connects, reads VERSION, logs the
 * operator in, holds the session while the channel stays up, and starts again when it falls over:
 *
 * <pre>
 *     DISCONNECTED -&gt; CONNECTING -&gt; READY
 *           ^                          |
 *           +------- FAULTED &lt;---------+
 * </pre>
 */
public final class PrinterSession {

    /** Told about every state change, on the thread that made it. */
    @FunctionalInterface
    public interface StateHook {

        /** {@code session} has just moved to {@code state}. */
        void changed(PrinterSession session, SessionState state) throws InterruptedException;
    }

    private final String printerId;
    private final Supplier<? extends Transport> factory;
    private final Credentials credentials;
    private final LinkSettings linkSettings;
    private final SessionSettings sessionSettings;
    private final StateHook onState;
    private final EventLog log;
    private final ReentrantLock readyLock = new ReentrantLock();
    private final Condition readyChanged = readyLock.newCondition();
    private volatile SessionState state = SessionState.DISCONNECTED;
    private volatile boolean ready;
    private volatile Channel channel;
    private volatile Authenticator auth;
    private volatile Dialect dialect;
    private volatile Exception lastError;
    private volatile int connects;
    private volatile int faults;
    private Thread task;

    private PrinterSession(Builder builder) {
        this.printerId = builder.printerId;
        this.factory = builder.factory;
        this.credentials = builder.credentials;
        this.linkSettings = builder.linkSettings;
        this.sessionSettings = builder.sessionSettings;
        this.onState = builder.onState;
        this.log = EventLog.get("nozzlewright.session", "printer", printerId);
    }

    /** A session for {@code printerId} over links from {@code factory}, with the default settings. */
    public static Builder builder(String printerId, Supplier<? extends Transport> factory) {
        return new Builder(printerId, factory);
    }

    /** The printer this session talks to. */
    public String printerId() {
        return printerId;
    }

    /** The current state. */
    public SessionState state() {
        return state;
    }

    /** What the machine reported at VERSION, once it has. */
    public Optional<Dialect> dialect() {
        return Optional.ofNullable(dialect);
    }

    /** Who is logged in, if anyone. */
    public Optional<Operator> operator() {
        Authenticator held = auth;
        return held == null ? Optional.empty() : held.operator();
    }

    /** The live channel, for callers that need to talk to it directly. */
    public Optional<Channel> channel() {
        return Optional.ofNullable(channel);
    }

    /** How many times the session reached READY. */
    public int connects() {
        return connects;
    }

    /** How many times a session failed to come up or was lost. */
    public int faults() {
        return faults;
    }

    /** The fault behind the last failure, if there was one. */
    public Optional<Exception> lastError() {
        return Optional.ofNullable(lastError);
    }

    /** Start supervising. Returns as soon as the supervisor thread is running. */
    public synchronized void start() {
        if (task != null) {
            return;
        }
        task = Thread.ofPlatform().daemon().name("nozzlewright-supervisor").start(this::superviseQuietly);
    }

    /** Stop supervising and close everything down. */
    public void stop() throws InterruptedException {
        Thread running;
        synchronized (this) {
            running = task;
            task = null;
        }
        if (running != null) {
            running.interrupt();
            running.join();
        }
        teardown();
        setState(SessionState.DISCONNECTED);
    }

    /** Wait until the session is usable, or {@code timeoutSeconds} runs out. True when it is. */
    public boolean waitReady(double timeoutSeconds) throws InterruptedException {
        long left = Durations.ofSeconds(timeoutSeconds).toNanos();
        readyLock.lock();
        try {
            while (!ready) {
                if (left <= 0) {
                    return false;
                }
                left = readyChanged.awaitNanos(left);
            }
            return true;
        } finally {
            readyLock.unlock();
        }
    }

    /** Wait up to thirty seconds for the session to be usable. */
    public boolean waitReady() throws InterruptedException {
        return waitReady(30.0);
    }

    /**
     * Send a command through the supervised channel.
     *
     * @throws LinkClosed when the session is not ready
     * @throws io.github.rkaushikdev.nozzlewright.PermissionDenied when the operator may not send it
     */
    public Response command(String verb, Object... values) throws InterruptedException {
        return readyChannel(verb).send(verb, values);
    }

    /** As {@link #command}, handing back a refusal as a response rather than failing. */
    public Response commandAnyStatus(String verb, Object... values) throws InterruptedException {
        return readyChannel(verb).sendAnyStatus(verb, values);
    }

    private Channel readyChannel(String verb) {
        Channel held = channel;
        if (held == null || state != SessionState.READY) {
            throw new LinkClosed("session for " + printerId + " is not ready");
        }
        Authenticator signedIn = auth;
        if (signedIn != null && signedIn.loggedIn()) {
            signedIn.require(verb);
        }
        return held;
    }

    private void superviseQuietly() {
        try {
            supervise();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    /** Connect, hold, reconnect. Runs until interrupted. */
    private void supervise() throws InterruptedException {
        Dialer dialer = Dialer.builder(factory)
                .backoffSeconds(linkSettings.reconnectBackoffSeconds())
                .build();
        double settleSeconds = linkSettings.reconnectBackoffSeconds().get(0);
        while (true) {
            setState(SessionState.CONNECTING);
            DialOutcome outcome = dialer.dial();
            if (outcome.transport().isEmpty()) {
                lastError = outcome.lastError().orElse(null);
                setState(SessionState.FAULTED);
                continue;
            }
            try {
                bringUp(outcome.transport().get());
            } catch (NozzlewrightError error) {
                lastError = error;
                faults++;
                log.warning("session start failed", "error", error.getMessage());
                teardown();
                setState(SessionState.FAULTED);
                // A machine that opens a link and then refuses to identify itself would otherwise
                // be redialled as fast as the loop runs.
                Thread.sleep(Durations.ofSeconds(settleSeconds));
                continue;
            }
            connects++;
            setState(SessionState.READY);
            hold();
            teardown();
            setState(SessionState.DISCONNECTED);
        }
    }

    /** Open the channel, read VERSION, log the operator back in. */
    private void bringUp(Transport transport) throws InterruptedException {
        Channel made = new Channel(transport, Dialect.REVISION_TWO, sessionSettings);
        channel = made;
        made.start();
        Response version = made.send("VERSION");
        Dialect found = Dialect.fromVersionRecord(version.record());
        dialect = found;
        made.dialect(found);
        Authenticator authenticator = new Authenticator(made);
        auth = authenticator;
        if (credentials != null) {
            authenticator.login(credentials.name(), credentials.pin());
        }
        log.info("session up", "family", found.family(), "revision", found.revision(), "build", found.build());
    }

    /** Sit on a healthy session until the channel reports a fault. */
    private void hold() throws InterruptedException {
        Channel held = channel;
        if (held == null) {
            return;
        }
        while (held.running()) {
            Thread.sleep(Durations.ofSeconds(sessionSettings.keepaliveIntervalSeconds()));
        }
        faults++;
        lastError = held.fault().orElse(null);
        log.warning(
                "session lost", "error", held.fault().map(Throwable::getMessage).orElse("None"));
    }

    /** Close the channel and the link, and forget the operator. */
    private void teardown() throws InterruptedException {
        Authenticator held = auth;
        if (held != null) {
            held.forget();
            auth = null;
        }
        Channel current = channel;
        channel = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } finally {
            current.transport().close();
        }
    }

    /** Move to {@code next} and tell anyone who asked to be told. */
    private void setState(SessionState next) throws InterruptedException {
        if (next == state) {
            return;
        }
        state = next;
        readyLock.lock();
        try {
            ready = next == SessionState.READY;
            readyChanged.signalAll();
        } finally {
            readyLock.unlock();
        }
        log.info("state", "state", next.value());
        if (onState != null) {
            onState.changed(this, next);
        }
    }

    /** Assembles a {@link PrinterSession}. */
    public static final class Builder {
        private final String printerId;
        private final Supplier<? extends Transport> factory;
        private Credentials credentials;
        private LinkSettings linkSettings = LinkSettings.defaults();
        private SessionSettings sessionSettings = SessionSettings.defaults();
        private StateHook onState;

        private Builder(String printerId, Supplier<? extends Transport> factory) {
            this.printerId = printerId;
            this.factory = factory;
        }

        /** Log this operator in on every connect. */
        public Builder credentials(Credentials value) {
            credentials = value;
            return this;
        }

        /** How links are opened and redialled. */
        public Builder linkSettings(LinkSettings value) {
            linkSettings = value;
            return this;
        }

        /** How the channel behaves. */
        public Builder sessionSettings(SessionSettings value) {
            sessionSettings = value;
            return this;
        }

        /** Told about every state change. */
        public Builder onState(StateHook value) {
            onState = value;
            return this;
        }

        /** The session, not yet started. */
        public PrinterSession build() {
            return new PrinterSession(this);
        }
    }
}
