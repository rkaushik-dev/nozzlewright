package io.github.rkaushikdev.nozzlewright;

/**
 * Base class for every error the controller raises.
 *
 * <p>Callers can put one catch around a printer interaction. The subclasses follow the places
 * where the handheld actually has to do something different: a transport fault means reconnect, a
 * protocol fault means the firmware and the controller disagree, and a printer fault means the
 * machine said no.
 */
public class NozzlewrightError extends RuntimeException {

    /** An error with a message. */
    public NozzlewrightError(String message) {
        super(message);
    }

    /** An error with a message and the fault underneath it. */
    public NozzlewrightError(String message, Throwable cause) {
        super(message, cause);
    }
}
