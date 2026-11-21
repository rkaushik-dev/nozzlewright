package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.buildinfo.BuildInfo;
import io.github.rkaushikdev.nozzlewright.deploy.Deploy;
import io.github.rkaushikdev.nozzlewright.deploy.DeploymentPlan;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.telemetry.Logs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The command line tool, {@code nozzlewright}.
 *
 * <p>Two audiences. A commissioning engineer uses it on a laptop plugged into the cell switch, and
 * a gateway runs the same code from a service unit. Both want the same commands to mean the same
 * thing, so nothing here is interactive and every command prints something a script can read: plain
 * {@code key=value} text by default and JSON on request, never a table drawn in box characters, which
 * the serial console on a panel PC renders as noise.
 *
 * <p>The exit code is the answer: nought when it worked, one when a command refused or a machine
 * said no (the reason on standard error, never a stack trace), two when the arguments were wrong.
 * No command starts the HTTP service; each one opens what it needs and closes it.
 */
@Command(
        name = "nozzlewright",
        description = "Fleet control for Halvex coding printers.",
        subcommands = {
            RegistryCommands.class,
            PrinterCommands.class,
            LabelCommands.class,
            DbCommands.class,
            KeyCommands.class,
            Main.Install.class,
            Main.Version.class
        })
public final class Main {

    /** It worked. */
    public static final int OK = 0;

    /** A refusal: the command could not do what was asked. */
    public static final int REFUSED = 1;

    /** The arguments were wrong. */
    public static final int MISUSED = 2;

    private final Terminal terminal;
    private Context context;

    @Option(names = "--state-dir", description = "Where the site file, the registry and the database live.")
    private Path stateDir;

    @Option(names = "--user", defaultValue = "", description = "Operator to log in as.")
    private String user;

    @Option(names = "--pin", defaultValue = "", description = "Operator PIN.")
    private String pin;

    @Option(names = "--log-level", defaultValue = "WARNING", description = "Log level for the tool itself.")
    private String logLevel;

    @Option(names = "--simulate", description = "Talk to the built in machine model instead of a printer.")
    private boolean simulate;

    @Option(names = "--help", usageHelp = true, description = "Show this message and exit.")
    private boolean help;

    Main(Terminal terminal) {
        this.terminal = terminal;
    }

    /** The console script. */
    public static void main(String... args) {
        System.exit(run(Terminal.system(), args));
    }

    /**
     * Run one command line and return what the process should exit with.
     *
     * <p>The tool's own log goes to the terminal's error stream at {@code --log-level}, in the format
     * the settings name, before the command runs.
     */
    public static int run(Terminal terminal, String... args) {
        Main main = new Main(terminal);
        CommandLine line = new CommandLine(main)
                .setExpandAtFiles(false)
                .setOverwrittenOptionsAllowed(true)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setOut(terminal.out())
                .setErr(terminal.err());
        line.setExecutionStrategy(parsed -> {
                    main.configureLogging();
                    return new CommandLine.RunLast().execute(parsed);
                })
                .setExecutionExceptionHandler((trouble, failing, parsed) -> refused(terminal, failing, trouble, args));
        try {
            return line.execute(args);
        } finally {
            terminal.out().flush();
            terminal.err().flush();
        }
    }

    private static int refused(Terminal terminal, CommandLine failing, Exception trouble, String[] args)
            throws Exception {
        if (trouble instanceof CommandFailed refusal) {
            terminal.err().println(refusal.getMessage());
            return refusal.exitCode();
        }
        if (trouble instanceof NozzlewrightError refusal) {
            terminal.err().println(refusal.getMessage());
            return REFUSED;
        }
        if (trouble instanceof CommandLine.ParameterException misuse) {
            return failing.getParameterExceptionHandler().handleParseException(misuse, args);
        }
        if (trouble instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            terminal.err().println("interrupted");
            return REFUSED;
        }
        throw trouble;
    }

    private void configureLogging() {
        Settings settings = Settings.load(terminal.environment());
        Logs.configure(logLevel, settings.logFormat(), new WriterStream(terminal.err()));
    }

    /** The terminal this run talks to. */
    Terminal terminal() {
        return terminal;
    }

    /** The shared context, built from the environment, the options and the site file on first use. */
    Context context() {
        if (context == null) {
            context = Context.builder(Settings.load(terminal.environment()))
                    .stateDir(stateDir)
                    .user(user)
                    .pin(pin)
                    .simulate(simulate)
                    .machines(terminal.machines())
                    .build();
        }
        return context;
    }

    /** Write the service unit and environment file for this site. */
    @Command(
            name = "install",
            description = {
                "Write the service unit and environment file for this site.",
                "",
                "Printed by default, because the person running this is usually reading it before"
                        + " they commit to it. Pass an output directory to write both files and the"
                        + " commands that install them."
            })
    static final class Install extends Leaf {

        @Option(names = "--out", description = "Write the files here instead of printing them.")
        private Path target;

        @Option(
                names = "--service-state-dir",
                defaultValue = DeploymentPlan.DEFAULT_STATE_DIR,
                description = "Where the gateway keeps state.")
        private String serviceStateDir;

        @Option(
                names = "--executable",
                defaultValue = DeploymentPlan.DEFAULT_EXECUTABLE,
                description = "Where the tool is installed.")
        private String executable;

        @Option(names = "--user", defaultValue = "nozzlewright", description = "Service user.")
        private String serviceUser;

        @Option(names = "--port", defaultValue = "8420", description = "Port to serve on.")
        private int port;

        @Option(
                names = "--poll-interval",
                defaultValue = "15.0",
                description = "Seconds between readings of a machine.")
        private double pollInterval;

        @Override
        public Integer call() {
            range("--port", port, 1, 65535);
            DeploymentPlan plan = DeploymentPlan.builder(context().settings().siteId())
                    .stateDir(serviceStateDir)
                    .executable(executable)
                    .user(serviceUser)
                    .group(serviceUser)
                    .port(port)
                    .pollIntervalSeconds(pollInterval)
                    .build();
            try {
                plan.validate();
            } catch (IllegalArgumentException error) {
                throw Output.fail(error.getMessage());
            }
            String envName = Path.of(plan.envPath()).getFileName().toString();
            if (target == null) {
                out().println("# " + plan.unitName());
                out().println(Deploy.renderUnit(plan));
                out().println("# " + plan.envPath());
                out().println(Deploy.renderEnv(plan));
                out().println("# then, as root");
                out().println(Deploy.renderInstallNotes(plan));
                return OK;
            }
            try {
                Files.createDirectories(target);
                Files.writeString(target.resolve(plan.unitName()), Deploy.renderUnit(plan), StandardCharsets.UTF_8);
                Files.writeString(target.resolve(envName), Deploy.renderEnv(plan), StandardCharsets.UTF_8);
                Files.writeString(
                        target.resolve("install.sh"), Deploy.renderInstallNotes(plan), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            out().println("wrote " + plan.unitName() + ", " + envName + " and install.sh into " + target);
            return OK;
        }
    }

    /** Print what is running here. */
    @Command(
            name = "version",
            description = {
                "Print what is running here.",
                "",
                "The bare version is what a script wants and everything else is what a support call"
                        + " wants, so both are here and the long form is the default."
            })
    static final class Version extends Leaf {

        @Option(names = "--short", description = "Just the version number.")
        private boolean brief;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            if (brief) {
                out().println(Nozzlewright.VERSION);
                return OK;
            }
            BuildInfo info = BuildInfo.current();
            if (asJson) {
                Output.emit(out(), info.asMap(), true);
                return OK;
            }
            out().println(info.render());
            return OK;
        }
    }
}
