package io.github.rkaushikdev.nozzlewright.gateway;

import io.github.rkaushikdev.nozzlewright.api.Api;
import io.github.rkaushikdev.nozzlewright.api.Services;
import io.github.rkaushikdev.nozzlewright.cli.Context;
import io.github.rkaushikdev.nozzlewright.fleet.AlarmState;
import io.github.rkaushikdev.nozzlewright.fleet.Cadence;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.LogSink;
import io.github.rkaushikdev.nozzlewright.fleet.Notifier;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.RestClientPoster;
import io.github.rkaushikdev.nozzlewright.fleet.Sink;
import io.github.rkaushikdev.nozzlewright.fleet.StatsCollector;
import io.github.rkaushikdev.nozzlewright.fleet.WebhookSink;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.NotifySettings;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.AlarmChange;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Janitor;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RecordOutcome;
import io.github.rkaushikdev.nozzlewright.storage.Recorder;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import io.github.rkaushikdev.nozzlewright.storage.SweepResult;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.telemetry.Metrics;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The line gateway: one site, supervised.
 *
 * <p>Everything else in this code base is a piece. This is the box in the cabinet: it brings up the
 * database, reads the registry, starts a session and a poll loop per machine, writes what it sees, and
 * hands the API what it needs to serve.
 *
 * <p>Start up order matters and is worth reading once. The schema is upgraded before anything opens a
 * session, because a poll that arrives against a half migrated database writes a row that the next
 * upgrade has to cope with. The registry is loaded into the database before the poller starts, because
 * a reading for a machine that is not in the printers table is dropped. And the API only starts
 * serving once both are done, so a wall board never shows an empty site that is actually still
 * starting.
 *
 * <p>Shutdown is the same list backwards, and it waits. A gateway that exits while a poll is in flight
 * leaves a session open on the machine, and the firmware takes three seconds to notice, which is three
 * seconds during which the handheld cannot log in.
 *
 * <p>{@link #prepare}, {@link #start} and {@link #stop} may be called from any thread; readings are
 * recorded on the poller's threads.
 */
public final class Gateway {

    /** The database's name inside the state directory; H2 adds its own suffix to the file. */
    public static final String DATABASE_NAME = "site";

    /** The saved registry's name inside the state directory. */
    public static final String REGISTRY_NAME = "registry.json";

    /** Seconds between readings of one machine when nobody says. */
    public static final double DEFAULT_POLL_INTERVAL_SECONDS = 15.0;

    private static final EventLog LOG = EventLog.get("nozzlewright.gateway");

    /**
     * Stands in for a machine that has left the registry mid poll, so a notice can still go out about
     * the alarm that was already up on it.
     */
    private static final PrinterRecord NO_RECORD = new PrinterRecord("gone", "0.0.0.0:0");

    private final Path stateDir;
    private final double pollIntervalSeconds;
    private final Retention retention;
    private final Set<String> apiKeys;
    private final Optional<Credentials> credentials;
    private final boolean simulate;
    private final StatsCollector stats = new StatsCollector();

    private volatile Settings settings;
    private volatile Database database;
    private volatile Registry registry;
    private volatile SiteConfig site = SiteConfig.empty();
    private volatile FleetPoller poller;
    private volatile Recorder recorder;
    private volatile Janitor janitor;
    private volatile Notifier notifier;
    private volatile Metrics metrics;
    private volatile RestClientPoster webhookClient;
    private volatile Services services;
    private volatile Api api;
    private volatile boolean running;

    private Gateway(Builder builder) {
        this.settings = builder.settings;
        this.stateDir = builder.stateDir == null ? builder.settings.stateDir() : builder.stateDir;
        this.pollIntervalSeconds = builder.pollIntervalSeconds;
        this.retention = builder.retention;
        this.apiKeys = Collections.unmodifiableSet(new TreeSet<>(builder.apiKeys));
        this.credentials = Optional.ofNullable(builder.credentials);
        this.simulate = builder.simulate;
        this.registry = new Registry();
        try {
            Files.createDirectories(stateDir);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot make the state directory " + stateDir, error);
        }
    }

    /** A builder for a gateway over {@code settings}; the state directory defaults to theirs. */
    public static Builder builder(Settings settings) {
        return new Builder(settings);
    }

    /** The settings, with the site file layered under them once the gateway is prepared. */
    public Settings settings() {
        return settings;
    }

    /** Where the site file, the registry and the database live. */
    public Path stateDir() {
        return stateDir;
    }

    /** Seconds between readings of one machine, before the cadence moves it. */
    public double pollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /** How long each table keeps its rows. */
    public Retention retention() {
        return retention;
    }

    /** The API keys the service will accept, as given; empty for an open gateway. */
    public Set<String> apiKeys() {
        return apiKeys;
    }

    /** The operator every session logs in as, when one was given. */
    public Optional<Credentials> credentials() {
        return credentials;
    }

    /** Whether links go to the built in machine model. */
    public boolean simulate() {
        return simulate;
    }

    /** Where the database lives, before H2 adds its suffix. */
    public Path databasePath() {
        return stateDir.resolve(DATABASE_NAME);
    }

    /** The JDBC URL of the database in the state directory. */
    public String databaseUrl() {
        return Database.fileUrl(databasePath());
    }

    /** Where the saved registry lives. */
    public Path registryPath() {
        return stateDir.resolve(REGISTRY_NAME);
    }

    /** The open database, once prepared and until stopped. */
    public Optional<Database> database() {
        return Optional.ofNullable(database);
    }

    /** The machines on this site: empty until prepared. */
    public Registry registry() {
        return registry;
    }

    /** The site file as read when the gateway was prepared; empty before that. */
    public SiteConfig site() {
        return site;
    }

    /** The poller, while running. */
    public Optional<FleetPoller> poller() {
        return Optional.ofNullable(poller);
    }

    /** What writes the readings down, once prepared. */
    public Optional<Recorder> recorder() {
        return Optional.ofNullable(recorder);
    }

    /** What keeps the history inside its retention, once prepared. */
    public Optional<Janitor> janitor() {
        return Optional.ofNullable(janitor);
    }

    /** Who hears about alarms, once prepared. */
    public Optional<Notifier> notifier() {
        return Optional.ofNullable(notifier);
    }

    /** The metrics the service exposes, once prepared. */
    public Optional<Metrics> metrics() {
        return Optional.ofNullable(metrics);
    }

    /** The HTTP client the webhook sink posts with, while one is configured and the gateway is up. */
    public Optional<RestClientPoster> webhookClient() {
        return Optional.ofNullable(webhookClient);
    }

    /** The rolling figures per machine the fleet summary reads. */
    public StatsCollector stats() {
        return stats;
    }

    /** What the API routes reach for, once prepared. */
    public Optional<Services> services() {
        return Optional.ofNullable(services);
    }

    /** The API to serve, once prepared. */
    public Optional<Api> api() {
        return Optional.ofNullable(api);
    }

    /** True between start and stop. */
    public boolean running() {
        return running;
    }

    /**
     * Open the database, load the registry and build the API.
     *
     * <p>Nothing here starts a thread, which is what lets a service unit fail fast on a broken database
     * or site file rather than fail slowly on the first request.
     *
     * @throws io.github.rkaushikdev.nozzlewright.ConfigError when the site file cannot be used
     */
    public synchronized Api prepare() {
        database = Database.file(databasePath());
        Schema.upgrade(database);

        site = SiteConfig.load(SiteConfig.sitePath(stateDir));
        settings = site.applyTo(settings);
        registry = loadRegistry();
        syncRegistry();

        metrics = new Metrics(settings.siteId());
        recorder = new Recorder(database);
        janitor = Janitor.builder(database).retention(retention).compacts(true).build();
        notifier = buildNotifier();
        Services built = new Services(settings, database, registry, stats);
        built.apiKeys(apiKeys);
        services = built;
        api = Api.of(built);
        LOG.info(
                "gateway prepared",
                "site",
                settings.siteId(),
                "machines",
                registry.ids().size(),
                "database",
                databaseUrl());
        return api;
    }

    /** Bring up a session and a poll loop for every enabled machine, preparing first if need be. */
    public synchronized void start() {
        if (running) {
            return;
        }
        if (api == null) {
            prepare();
        }
        FleetPoller started = FleetPoller.builder(registry, this::sessionFor)
                .intervalSeconds(pollIntervalSeconds)
                .onResult(this::record)
                .cadence(new Cadence(pollIntervalSeconds))
                .build();
        started.start();
        poller = started;
        services.poller(started);
        janitor.start();
        running = true;
        LOG.info("gateway started", "machines", registry.enabled().size());
    }

    /**
     * Stop polling, close every session, then close the database. Stopping a gateway that is not
     * running only lets go of the database.
     */
    public synchronized void stop() throws InterruptedException {
        if (!running) {
            dispose();
            return;
        }
        running = false;
        if (janitor != null) {
            janitor.stop();
        }
        FleetPoller stopping = poller;
        if (stopping != null) {
            stopping.stop();
            poller = null;
        }
        if (services != null) {
            services.noPoller();
        }
        RestClientPoster client = webhookClient;
        if (client != null) {
            client.close();
            webhookClient = null;
        }
        dispose();
        LOG.info("gateway stopped");
    }

    /** Delete everything past its retention, once, right now; nothing before the gateway is prepared. */
    public SweepResult sweep() {
        Janitor sweeping = janitor;
        if (sweeping == null) {
            return SweepResult.NONE;
        }
        SweepResult result = sweeping.sweep();
        Metrics counting = metrics;
        if (counting != null) {
            Map<String, Long> pages = sweeping.pageStats();
            counting.observeSweep(sweeping.total().asMap(), pages.getOrDefault("bytes", 0L));
        }
        return result;
    }

    /**
     * Let go of the database without stopping anything, for a gateway that was prepared and never
     * started or one whose start went wrong. Safe to call more than once.
     */
    public synchronized void dispose() {
        Database open = database;
        if (open != null) {
            open.close();
            database = null;
        }
    }

    /**
     * Everything that wants to know about one reading.
     *
     * <p>The notifier counters are folded in at the end rather than alongside the reading counters,
     * because the notices this reading causes have not gone out yet at the top of the method and a
     * scrape landing in between would be one behind for the whole poll interval.
     */
    void record(PollResult result) throws InterruptedException {
        stats.observe(result);
        Metrics counting = metrics;
        if (counting != null) {
            counting.observe(result);
        }
        Recorder writing = recorder;
        if (writing == null) {
            return;
        }
        RecordOutcome outcome = writing.record(result);
        Notifier telling = notifier;
        if (telling != null && !outcome.changes().isEmpty()) {
            String line = registry.find(result.printerId()).orElse(NO_RECORD).line();
            for (AlarmChange change : outcome.changes()) {
                AlarmState state = change.openNow()
                        ? AlarmState.open(change.printerId(), change.name(), change.severity())
                        : AlarmState.cleared(change.printerId(), change.name(), change.severity());
                telling.announce(state.withAcknowledged(change.acknowledged()).onLine(line));
            }
        }
        if (counting != null && telling != null) {
            counting.observeNotifier(telling.stats().asMap());
        }
    }

    /** Swap the notifier for another, so a test can listen in on what the gateway announces. */
    void notifier(Notifier value) {
        notifier = value;
    }

    /**
     * Assemble the sinks this gateway was configured with.
     *
     * <p>The log sink is always there. A gateway with no webhook still leaves a trail of what went up
     * and when, which is the thing a shift handover needs and the thing a chat room is worst at keeping.
     */
    private Notifier buildNotifier() {
        NotifySettings wanted = settings.notices();
        List<Sink> sinks = new ArrayList<>();
        sinks.add(new LogSink());
        if (!wanted.webhookUrl().isEmpty()) {
            Duration timeout = Durations.ofSeconds(wanted.webhookTimeoutSeconds());
            RestClientPoster client = new RestClientPoster(timeout);
            webhookClient = client;
            sinks.add(new WebhookSink(wanted.webhookUrl(), client)
                    .withToken(wanted.webhookToken())
                    .withTimeoutSeconds(wanted.webhookTimeoutSeconds()));
            LOG.info("alarm webhook configured", "url", wanted.webhookUrl());
        }
        return Notifier.builder(settings.siteId())
                .sinks(sinks)
                .floor(Severity.parse(wanted.floor()))
                .quiet(minutes(wanted.quietMinutes()))
                .reminder(minutes(wanted.reminderMinutes()))
                .build();
    }

    private static Duration minutes(double value) {
        return Durations.ofSeconds(value * 60.0);
    }

    /** A supervised session for one registry record. */
    private PrinterSession sessionFor(PrinterRecord record) {
        Settings current = settings;
        PrinterSession.Builder builder = PrinterSession.builder(
                        record.printerId(), () -> Context.buildTransport(record, current, simulate))
                .linkSettings(current.link())
                .sessionSettings(current.session());
        credentials.ifPresent(builder::credentials);
        return builder.build();
    }

    /**
     * The machines this site has, from the file that owns them.
     *
     * <p>A site file that names machines is the whole list. Otherwise the saved registry is, and a site
     * with neither starts empty and waits for somebody to add one.
     */
    private Registry loadRegistry() {
        if (!site.printers().isEmpty()) {
            return site.registry();
        }
        if (Files.exists(registryPath())) {
            return Registry.load(registryPath());
        }
        return new Registry(settings.siteId());
    }

    /** Make sure every registered machine has a row. */
    private void syncRegistry() {
        List<PrinterRecord> machines = registry.all();
        database.run(unit -> {
            PrinterRepo repo = new PrinterRepo(unit);
            machines.forEach(repo::upsert);
        });
    }

    /** Assembles a {@link Gateway}. */
    public static final class Builder {
        private final Settings settings;
        private Path stateDir;
        private double pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;
        private Retention retention = Retention.defaults();
        private Set<String> apiKeys = Set.of();
        private Credentials credentials;
        private boolean simulate;

        private Builder(Settings settings) {
            this.settings = settings;
        }

        /** The state directory, in place of the settings' own. */
        public Builder stateDir(Path value) {
            stateDir = value;
            return this;
        }

        /** Seconds between readings of one machine. */
        public Builder pollIntervalSeconds(double value) {
            pollIntervalSeconds = value;
            return this;
        }

        /** How long each table keeps its rows. */
        public Builder retention(Retention value) {
            retention = value;
            return this;
        }

        /** The API keys the service accepts, plain or hashed; none leaves it open. */
        public Builder apiKeys(Set<String> value) {
            apiKeys = Set.copyOf(value);
            return this;
        }

        /** The operator every session logs in as; {@code null} for none. */
        public Builder credentials(Credentials value) {
            credentials = value;
            return this;
        }

        /** Whether links go to the built in machine model. */
        public Builder simulate(boolean value) {
            simulate = value;
            return this;
        }

        /**
         * The gateway, its state directory made if it was not there.
         *
         * @throws UncheckedIOException when the state directory cannot be made
         */
        public Gateway build() {
            return new Gateway(this);
        }
    }
}
