package io.github.rkaushikdev.nozzlewright.cli;

/**
 * A command refusing, with the one line that says why.
 *
 * <p>Thrown rather than printed so the call site reads {@code throw Output.fail(...)} and a reader
 * can see the command ends there. The command line prints the message to standard error and exits
 * with {@link #exitCode()}, never with a stack trace: a technician cannot act on one.
 */
public final class CommandFailed extends RuntimeException {

    private final int exitCode;

    /** A refusal exiting one. */
    public CommandFailed(String message) {
        this(message, 1);
    }

    /** A refusal exiting {@code exitCode}. */
    public CommandFailed(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    /** What the process exits with. */
    public int exitCode() {
        return exitCode;
    }
}
