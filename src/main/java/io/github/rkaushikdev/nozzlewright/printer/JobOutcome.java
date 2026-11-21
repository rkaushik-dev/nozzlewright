package io.github.rkaushikdev.nozzlewright.printer;

/** How a print run ended. */
public enum JobOutcome {
    COMPLETE("complete"),
    STOPPED("stopped"),
    FAULTED("faulted"),
    REFUSED("refused"),
    TIMED_OUT("timed_out");

    private final String value;

    JobOutcome(String value) {
        this.value = value;
    }

    /** The word the log, the history and the operator screen use. */
    public String value() {
        return value;
    }

    /** True when the job printed everything it was asked to. */
    public boolean ok() {
        return this == COMPLETE;
    }

    @Override
    public String toString() {
        return value;
    }
}
