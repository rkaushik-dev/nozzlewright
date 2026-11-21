package io.github.rkaushikdev.nozzlewright;

/** The printer understood the controller and refused. */
public class PrinterError extends NozzlewrightError {

    /** A refusal described in words. */
    public PrinterError(String message) {
        super(message);
    }
}
