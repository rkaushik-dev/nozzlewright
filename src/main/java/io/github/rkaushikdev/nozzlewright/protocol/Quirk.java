package io.github.rkaushikdev.nozzlewright.protocol;

/**
 * Named behaviours that differ between firmware builds.
 *
 * <p>These are strings rather than an enum because field service keeps finding new ones, and a
 * technician should be able to add a quirk to a machine record from the handheld without a
 * firmware update on the controller side.
 */
public final class Quirk {

    /** Frames end in a bare LF. */
    public static final String BARE_LF = "bare_lf";

    /** The build carries no checksum, whatever its revision. */
    public static final String NO_CHECKSUM = "no_checksum";

    /** Records come padded with trailing commas. */
    public static final String PADDED_RECORDS = "padded_records";

    /** Writing to the label store is slow. */
    public static final String SLOW_STORE_WRITE = "slow_store_write";

    /** STATUS answers with bare values instead of keyed fields. */
    public static final String STATUS_WITHOUT_KEYS = "status_without_keys";

    private Quirk() {}
}
