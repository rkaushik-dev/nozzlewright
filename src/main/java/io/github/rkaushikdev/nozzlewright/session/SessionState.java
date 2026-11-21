package io.github.rkaushikdev.nozzlewright.session;

/**
 * Where one printer session is.
 *
 * <p>A fault always goes back through {@link #DISCONNECTED}, never straight to {@link #READY},
 * because the printer times its own session out when the radio drops and coming back without a
 * fresh LOGIN gives a machine that refuses every command with status three and no clue why.
 */
public enum SessionState {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    READY("ready"),
    FAULTED("faulted");

    private final String value;

    SessionState(String value) {
        this.value = value;
    }

    /** The word logs and the API use. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
