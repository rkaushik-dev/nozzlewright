package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.StatsCollector;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the API reaches for, built once by the gateway and handed to the application.
 *
 * <p>Handlers get their pieces from here rather than from a global, which keeps them testable.
 * Authentication is an API key in the {@value #API_KEY_HEADER} header, checked against the keys held
 * here. That is enough for a plant network where the API is not exposed outside the cell, and it is
 * deliberately not enough for anything else, which is said out loud here so nobody assumes
 * otherwise.
 *
 * <p>The poller and the keys can be changed while the application runs: the gateway hands the poller
 * over once it has started it, and a key change takes effect on the next request. Safe to use from
 * several threads.
 */
public final class Services {

    /** The header a client puts its key in. */
    public static final String API_KEY_HEADER = "X-NZW-Key";

    private final Settings settings;
    private final Database database;
    private final Registry registry;
    private final StatsCollector stats;
    private volatile PollView poller;
    private volatile Set<String> apiKeys = Set.of();
    private volatile KeyRing keyring = KeyRing.empty();

    /** Services over this database and registry, with a fresh stats collector and no keys. */
    public Services(Settings settings, Database database, Registry registry) {
        this(settings, database, registry, new StatsCollector());
    }

    /** Services over this database, registry and stats collector, with no keys. */
    public Services(Settings settings, Database database, Registry registry, StatsCollector stats) {
        this.settings = settings;
        this.database = database;
        this.registry = registry;
        this.stats = stats;
    }

    /** How the gateway was configured. */
    public Settings settings() {
        return settings;
    }

    /** The site database; units of work come from here. */
    public Database database() {
        return database;
    }

    /** The machines on the site. */
    public Registry registry() {
        return registry;
    }

    /** Production numbers. */
    public StatsCollector stats() {
        return stats;
    }

    /** The poller, once one is running. */
    public Optional<PollView> poller() {
        return Optional.ofNullable(poller);
    }

    /** Hand over a running poller. */
    public void poller(FleetPoller value) {
        poller(PollView.of(value));
    }

    /** Hand over what to read the polls through. */
    public void poller(PollView value) {
        poller = value;
    }

    /** Forget the poller, as when it stops. */
    public void noPoller() {
        poller = null;
    }

    /** The keys this gateway accepts, as configured: plain or as digests. */
    public Set<String> apiKeys() {
        return apiKeys;
    }

    /** Accept these keys from the next request on. */
    public void apiKeys(Set<String> value) {
        Set<String> copy = new TreeSet<>(value);
        keyring = KeyRing.of(copy);
        apiKeys = Set.copyOf(copy);
    }

    /**
     * The keys this gateway accepts, hashed.
     *
     * <p>Built from whatever was configured rather than held as given, because the gateway and the
     * tests both hand in a plain set and neither should have to know how a key is stored.
     */
    public KeyRing keyring() {
        return keyring;
    }

    /**
     * True when a key has to be presented.
     *
     * <p>A gateway shipped with no keys configured is open on the cell network on purpose, because
     * that is how the older handhelds work and locking them out at upgrade time would stop a line.
     */
    public boolean authRequired() {
        return keyring.any();
    }

    /**
     * The live session for one machine.
     *
     * @throws ApiProblem with status 503 when the poller is not running
     * @throws io.github.rkaushikdev.nozzlewright.NozzlewrightError when the poller has no session for it
     */
    public PrinterSession sessionFor(String printerId) {
        PollView running = poller;
        if (running == null) {
            throw new ApiProblem(503, "the poller is not running");
        }
        return running.session(printerId);
    }

    @Override
    public String toString() {
        return "Services[" + settings.siteId() + ", " + keyring.size() + " keys, poller "
                + (poller == null ? "stopped" : "running") + "]";
    }

    static Map<String, String> keyChallenge() {
        return Map.of("WWW-Authenticate", API_KEY_HEADER);
    }
}
