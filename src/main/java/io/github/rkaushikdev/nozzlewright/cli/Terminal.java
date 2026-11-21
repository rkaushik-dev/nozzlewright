package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.transport.Responder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a command talks to: the environment it was started in, the keyboard, the two streams, and
 * any machine models standing in for real printers.
 *
 * <p>Everything a command prints goes to {@code out}; {@code err} carries refusals and the tool's own
 * log. A registered machine whose id is a key of {@code machines} is reached through that model over
 * a loopback link instead of its own endpoint, which is how a demo, a test or a training laptop works
 * a command against a machine that is not on the desk.
 *
 * @param environment the environment variables, as a shell spells them
 * @param in the keyboard
 * @param out where results go
 * @param err where refusals and the log go
 * @param machines machine models by registered id, standing in for those machines' links
 */
public record Terminal(
        Map<String, String> environment,
        BufferedReader in,
        PrintWriter out,
        PrintWriter err,
        Map<String, Responder> machines) {

    /** Copies both maps. */
    public Terminal {
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        machines = Collections.unmodifiableMap(new LinkedHashMap<>(machines));
    }

    /** A terminal with no machine models: every machine is reached on its own link. */
    public Terminal(Map<String, String> environment, BufferedReader in, PrintWriter out, PrintWriter err) {
        this(environment, in, out, err, Map.of());
    }

    /** The process's own environment, keyboard and streams. */
    public static Terminal system() {
        Charset charset = Charset.defaultCharset();
        return new Terminal(
                System.getenv(),
                new BufferedReader(new InputStreamReader(System.in, charset)),
                new PrintWriter(System.out, true, charset),
                new PrintWriter(System.err, true, charset));
    }

    /** The same terminal with these machine models standing in for real machines. */
    public Terminal withMachines(Map<String, ? extends Responder> value) {
        return new Terminal(environment, in, out, err, Collections.unmodifiableMap(new LinkedHashMap<>(value)));
    }
}
