package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.transport.Responder;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Running the command line inside a test: a terminal over strings, an environment of the test's
 * choosing and nothing else, and what came back.
 */
public final class Cli {

    /** What one run printed and exited with. */
    public record Result(int exitCode, String stdout, String stderr) {}

    private final Map<String, String> environment;
    private final Map<String, Responder> machines;
    private final String input;

    private Cli(Map<String, String> environment, Map<String, Responder> machines, String input) {
        this.environment = environment;
        this.machines = machines;
        this.input = input;
    }

    /** A clean environment, no machine models and nothing typed. */
    public static Cli clean() {
        return new Cli(Map.of(), Map.of(), "");
    }

    /** The same runner with this environment. */
    public Cli environment(Map<String, String> value) {
        return new Cli(value, machines, input);
    }

    /** The same runner with these machine models standing in for registered machines. */
    public Cli machines(Map<String, ? extends Responder> value) {
        return new Cli(environment, Map.copyOf(value), input);
    }

    /** The same runner with {@code value} typed at the keyboard. */
    public Cli input(String value) {
        return new Cli(environment, machines, value);
    }

    /** Run one command line. */
    public Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Terminal terminal = new Terminal(
                environment,
                new BufferedReader(new StringReader(input)),
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                machines);
        int code = Main.run(terminal, args);
        return new Result(code, out.toString(), err.toString());
    }

    /** Run one command line against the state directory {@code stateDir}. */
    public Result in(Path stateDir, String... args) {
        List<String> full = new ArrayList<>(List.of("--state-dir", stateDir.toString()));
        full.addAll(List.of(args));
        return run(full.toArray(String[]::new));
    }

    /** Run one command line against {@code stateDir} with a clean environment. */
    public static Result invoke(Path stateDir, String... args) {
        return clean().in(stateDir, args);
    }
}
