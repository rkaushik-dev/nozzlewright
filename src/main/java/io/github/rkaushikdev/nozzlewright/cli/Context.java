package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import io.github.rkaushikdev.nozzlewright.transport.Responder;
import io.github.rkaushikdev.nozzlewright.transport.RfcommTransport;
import io.github.rkaushikdev.nozzlewright.transport.TcpTransport;
import io.github.rkaushikdev.nozzlewright.transport.Transport;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What every command needs before it can do anything: where the registry is, where the database
 * is, and how to reach one machine.
 *
 * <p>Working that out in each command is how a tool ends up with four slightly different ideas of
 * where the state directory lives, so it is worked out here once. Connecting is deliberately
 * explicit: nothing opens a link until a command asks for one, because a technician running {@code
 * nozzlewright label check} on a train should not have the tool trying to dial a printer in a
 * factory.
 *
 * <p>The site file in the state directory is read when the context is made, and what it says is
 * layered under the settings: the environment wins over the file and the file over the defaults.
 */
public final class Context {

    /** The saved registry's name inside the state directory. */
    public static final String REGISTRY_NAME = "registry.json";

    /** The database's name inside the state directory; H2 adds its own suffix to the file. */
    public static final String DATABASE_NAME = "site";

    /** How long a command waits for a machine to come up when it does not say. */
    public static final double DEFAULT_CONNECT_SECONDS = 20.0;

    private final Settings settings;
    private final Path stateDir;
    private final String user;
    private final String pin;
    private final boolean simulate;
    private final Map<String, Responder> machines;
    private final SiteConfig site;

    private Context(Builder builder) {
        Path dir = builder.stateDir == null ? builder.settings.stateDir() : builder.stateDir;
        SiteConfig loaded = builder.site == null ? SiteConfig.load(SiteConfig.sitePath(dir)) : builder.site;
        this.stateDir = dir;
        this.site = loaded;
        this.settings = builder.site == null ? loaded.applyTo(builder.settings) : builder.settings;
        this.user = builder.user;
        this.pin = builder.pin;
        this.simulate = builder.simulate;
        this.machines = Collections.unmodifiableMap(new LinkedHashMap<>(builder.machines));
    }

    /** A builder for a context over {@code settings}; the state directory defaults to theirs. */
    public static Builder builder(Settings settings) {
        return new Builder(settings);
    }

    /**
     * The link one registry record describes.
     *
     * <p>{@code simulate} swaps the real link for the built in machine model, which is how the demo
     * on a training laptop works. A TCP endpoint is {@code host:port}; an RFCOMM one a radio address
     * reached on the settings' channel; anything else is a loopback with nothing behind it.
     *
     * @throws ConfigError when a TCP endpoint's port is not a number
     */
    public static Transport buildTransport(PrinterRecord record, Settings settings, boolean simulate) {
        if (simulate) {
            return new LoopbackTransport(new SimulatedPrinter(), record.endpoint());
        }
        double connect = settings.link().connectTimeoutSeconds();
        double write = settings.link().writeTimeoutSeconds();
        if (record.kind().equals("tcp")) {
            String endpoint = record.endpoint();
            int colon = endpoint.lastIndexOf(':');
            String host = colon < 0 ? "" : endpoint.substring(0, colon);
            int port;
            try {
                port = Math.toIntExact(Numbers.parseLong(endpoint.substring(colon + 1)));
            } catch (NumberFormatException | ArithmeticException error) {
                throw new ConfigError("endpoint", endpoint, "the port is not a number");
            }
            return new TcpTransport(host, port, connect, write);
        }
        if (record.kind().equals("rfcomm")) {
            return new RfcommTransport(record.endpoint(), settings.link().rfcommChannel(), connect, write, null, true);
        }
        return new LoopbackTransport(record.endpoint());
    }

    /** The settings, the site file layered under them. */
    public Settings settings() {
        return settings;
    }

    /** Where the site file, the registry and the database live. */
    public Path stateDir() {
        return stateDir;
    }

    /** The operator to log in as, empty for none. */
    public String user() {
        return user;
    }

    /** Whether links go to the built in machine model. */
    public boolean simulate() {
        return simulate;
    }

    /** The site file, as read. */
    public SiteConfig site() {
        return site;
    }

    /** Where the site registry lives. */
    public Path registryPath() {
        return stateDir.resolve(REGISTRY_NAME);
    }

    /** Where the database lives, before H2 adds its suffix. */
    public Path databasePath() {
        return stateDir.resolve(DATABASE_NAME);
    }

    /** The JDBC URL of the database in this state directory. */
    public String databaseUrl() {
        return Database.fileUrl(databasePath());
    }

    /** Open the database in this state directory; the caller closes it. */
    public Database openDatabase() {
        return Database.file(databasePath());
    }

    /**
     * The machines on this site.
     *
     * <p>The site file wins when it names any, because a file somebody edits and keeps in version
     * control beats a file the tool wrote itself. The saved registry is what a site that never wrote
     * a site file has, and a site with neither starts empty.
     */
    public Registry loadRegistry() {
        if (!site.printers().isEmpty()) {
            return site.registry();
        }
        if (!Files.exists(registryPath())) {
            return new Registry(settings.siteId());
        }
        return Registry.load(registryPath());
    }

    /** False when the machines came from the site file, which the tool must not rewrite. */
    public boolean saveAllowed() {
        return site.printers().isEmpty();
    }

    /**
     * Write the registry back.
     *
     * @throws ConfigError when the site file owns the machines
     */
    public void saveRegistry(Registry registry) {
        if (!saveAllowed()) {
            throw new ConfigError(
                    "registry",
                    site.source().map(Path::toString).orElse("None"),
                    "the machines come from the site file, edit that instead");
        }
        registry.save(registryPath());
    }

    /** The operator to log in as, when one was given. */
    public Optional<Credentials> credentials() {
        return user.isEmpty() ? Optional.empty() : Optional.of(new Credentials(user, pin));
    }

    /**
     * A supervised session for one registered machine, not yet started.
     *
     * @throws ConfigError when there is no such machine on this site
     */
    public PrinterSession sessionFor(String printerId) {
        PrinterRecord record = loadRegistry().get(printerId);
        Responder held = machines.get(record.printerId());
        PrinterSession.Builder builder = PrinterSession.builder(
                        record.printerId(),
                        () -> held != null
                                ? new LoopbackTransport(held, record.endpoint())
                                : buildTransport(record, settings, simulate))
                .linkSettings(settings.link())
                .sessionSettings(settings.session());
        credentials().ifPresent(builder::credentials);
        return builder.build();
    }

    /** Open a session to one machine, hand it to {@code work}, and always close it. */
    public <T> T connected(String printerId, SessionWork<T> work) throws InterruptedException {
        return connected(printerId, DEFAULT_CONNECT_SECONDS, work);
    }

    /**
     * Open a session to one machine, hand it to {@code work} once it is ready, and always close it.
     *
     * @throws ConfigError when the machine does not come up inside {@code timeoutSeconds}
     */
    public <T> T connected(String printerId, double timeoutSeconds, SessionWork<T> work) throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        session.start();
        try {
            if (!session.waitReady(timeoutSeconds)) {
                throw new ConfigError(
                        "printer", printerId, "did not answer inside " + Numbers.fixed(timeoutSeconds, 0) + " seconds");
            }
            return work.apply(session);
        } finally {
            session.stop();
        }
    }

    /** Something done with a ready session. */
    @FunctionalInterface
    public interface SessionWork<T> {

        /** Do it. */
        T apply(PrinterSession session) throws InterruptedException;
    }

    /** Assembles a {@link Context}. */
    public static final class Builder {

        private final Settings settings;
        private Path stateDir;
        private String user = "";
        private String pin = "";
        private boolean simulate;
        private Map<String, Responder> machines = Map.of();
        private SiteConfig site;

        private Builder(Settings settings) {
            this.settings = settings;
        }

        /** The state directory, in place of the settings' own. */
        public Builder stateDir(Path value) {
            stateDir = value;
            return this;
        }

        /** The operator to log in as. */
        public Builder user(String value) {
            user = value;
            return this;
        }

        /** The operator's PIN. */
        public Builder pin(String value) {
            pin = value;
            return this;
        }

        /** Whether links go to the built in machine model. */
        public Builder simulate(boolean value) {
            simulate = value;
            return this;
        }

        /** Machine models standing in for these machines' links. */
        public Builder machines(Map<String, ? extends Responder> value) {
            machines = new LinkedHashMap<>(value);
            return this;
        }

        /** The site, in place of reading the state directory's site file; the settings are taken as given. */
        public Builder site(SiteConfig value) {
            site = value;
            return this;
        }

        /**
         * The context, the site file read unless one was given.
         *
         * @throws ConfigError when the site file cannot be read
         */
        public Context build() {
            return new Context(this);
        }
    }
}
