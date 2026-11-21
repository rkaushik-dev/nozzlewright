package io.github.rkaushikdev.nozzlewright;

/** The machine is printing and the command is not allowed mid run. */
public class PrinterBusy extends PrinterError {

    /** A busy refusal, described in words. */
    public PrinterBusy(String message) {
        super(message);
    }
}
