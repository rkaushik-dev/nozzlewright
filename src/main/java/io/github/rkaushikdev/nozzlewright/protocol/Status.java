package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.Optional;

/**
 * Status codes the Halvex firmware puts in front of every response, and what to do about them.
 *
 * <p>Every response frame starts with a decimal status followed by a colon. Zero means the command
 * worked, everything else is a refusal. The labels match the ones printed in the service manual so
 * a technician reading the log and the machine display sees the same word.
 *
 * <p>The classification matters more than the number. Three questions get asked of a status all
 * over the controller: can the same command be sent again, does the operator need to log in first,
 * and is the link still worth holding open.
 */
public enum Status {
    OK(0, "ok", "command accepted", false, false, false),
    BUSY(1, "busy", "the machine is mid run and will not take this now", true, false, false),
    BAD_PARAM(2, "bad parameter", "the argument list did not match what the verb expects", false, false, false),
    NOT_LOGGED_IN(3, "not logged in", "no operator session is open on the printer", false, true, false),
    NO_PERMISSION(4, "no permission", "the operator level is below what this verb needs", false, true, false),
    UNKNOWN_CMD(5, "unknown command", "the firmware does not carry this verb", false, false, false),
    HEAD_FAULT(6, "head fault", "the print head reported an electrical fault", false, false, true),
    NO_CARTRIDGE(
            7, "no cartridge", "a pen position is empty or the cartridge did not authenticate", false, false, false),
    INK_OUT(8, "ink out", "the cartridge has no ink left for this pen", false, false, false),
    STORE_FULL(9, "store full", "the label store has no room for another template", false, false, false),
    NOT_FOUND(10, "not found", "the named label, user or group does not exist", false, false, false),
    OUT_OF_RANGE(11, "out of range", "the value is outside the limits the machine allows", false, false, false),
    CONFIG_LOCKED(12, "config locked", "configuration cannot change while printing is active", true, false, false),
    TIMEOUT(13, "timeout", "the firmware gave up waiting on an internal bus", true, false, false),
    BAD_CHECKSUM(14, "bad checksum", "the printer did not like the checksum we sent", true, false, false),
    OUT_OF_SEQUENCE(15, "out of sequence", "a multi frame transfer arrived in the wrong order", true, false, false);

    private final int code;
    private final StatusInfo info;

    Status(int code, String label, String detail, boolean retryable, boolean authFault, boolean fatal) {
        this.code = code;
        this.info = new StatusInfo(this, label, detail, retryable, authFault, fatal);
    }

    /** The number on the wire. */
    public int code() {
        return code;
    }

    /** The status a number stands for, when it is one the firmware documents. */
    public static Optional<Status> of(int code) {
        for (Status status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /**
     * Look up {@code status}, falling back to a generic entry for unknown codes.
     *
     * <p>Newer firmware adds codes nobody has seen. Treating those as a plain refusal is safer than
     * failing, because the caller already handles a non zero status and a crash here would take out
     * the whole poll loop.
     */
    public static StatusInfo describe(int status) {
        return of(status)
                .map(known -> known.info)
                .orElseGet(() -> new StatusInfo(
                        BAD_PARAM, "status " + status, "undocumented status from newer firmware", false, false, false));
    }

    /** True when sending the same command again may work. */
    public static boolean isRetryable(int status) {
        return describe(status).retryable();
    }

    /** True when the fix is to log in again rather than to resend. */
    public static boolean isAuthFault(int status) {
        return describe(status).authFault();
    }

    /** True when the link should be dropped and the printer flagged. */
    public static boolean isFatal(int status) {
        return describe(status).fatal();
    }
}
