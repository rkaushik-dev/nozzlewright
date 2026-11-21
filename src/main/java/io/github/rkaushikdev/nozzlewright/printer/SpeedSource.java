package io.github.rkaushikdev.nozzlewright.printer;

/** Where the line speed comes from: the shaft encoder, or a fixed AUTOSPEED setting. */
public enum SpeedSource {
    ENCODER,
    FIXED;

    /** The word the configuration uses, which is the constant's name. */
    public String value() {
        return name();
    }
}
