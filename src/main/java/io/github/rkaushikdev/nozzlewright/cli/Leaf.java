package io.github.rkaushikdev.nozzlewright.cli;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** What every command that does something shares: the way back to the root and its streams. */
abstract class Leaf implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--help", usageHelp = true, description = "Show this message and exit.")
    boolean help;

    /** The root command this one runs under. */
    Main main() {
        return (Main) spec.root().userObject();
    }

    /** The shared context, built the first time a command asks. */
    Context context() {
        return main().context();
    }

    /** Where results go. */
    PrintWriter out() {
        return main().terminal().out();
    }

    /** Where refusals go. */
    PrintWriter err() {
        return main().terminal().err();
    }

    /**
     * Refuse a number outside {@code low} to {@code high}, the way a usage error reads.
     *
     * @throws CommandLine.ParameterException when it is outside, which exits two
     */
    void range(String option, long value, long low, long high) {
        if (value < low || value > high) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    String.format(
                            Locale.ROOT,
                            "Invalid value for '%s': %d is not in the range %d<=x<=%d.",
                            option,
                            value,
                            low,
                            high));
        }
    }
}
