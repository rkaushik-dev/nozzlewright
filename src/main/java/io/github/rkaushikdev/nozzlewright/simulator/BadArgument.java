package io.github.rkaushikdev.nozzlewright.simulator;

/** The arguments of a setting verb did not make sense; the machine answers it as a bad parameter. */
final class BadArgument extends Exception {

    private static final long serialVersionUID = 1L;

    BadArgument(String message) {
        super(message);
    }
}
