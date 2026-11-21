package io.github.rkaushikdev.nozzlewright.storage;

/**
 * The things worth writing down in the audit trail.
 *
 * <p>Plain words rather than an enum, because a site that adds a step to its process should be able
 * to record it without a schema change, and because a value read back off an old database must not
 * blow up a new build.
 */
public final class AuditAction {

    /** A machine joined the site. */
    public static final String PRINTER_ADDED = "printer.added";

    /** A machine left the site. */
    public static final String PRINTER_REMOVED = "printer.removed";

    /** Settings were pushed to a machine. */
    public static final String CONFIG_APPLIED = "config.applied";

    /** A machine was put back to an earlier configuration. */
    public static final String CONFIG_ROLLED_BACK = "config.rolled_back";

    /** A label was stored. */
    public static final String LABEL_STORED = "label.stored";

    /** A label was selected on a machine. */
    public static final String LABEL_SELECTED = "label.selected";

    /** A run was started. */
    public static final String JOB_STARTED = "job.started";

    /** A run was finished. */
    public static final String JOB_FINISHED = "job.finished";

    /** Somebody took an alarm on. */
    public static final String ALARM_ACKNOWLEDGED = "alarm.acknowledged";

    /** Printing was stopped by hand. */
    public static final String PRINT_STOPPED = "print.stopped";

    private AuditAction() {}
}
