package io.github.rkaushikdev.nozzlewright.protocol;

/**
 * What the controller knows about one status code.
 *
 * @param status the documented status, {@link Status#BAD_PARAM} for an undocumented one
 * @param label the word the service manual uses
 * @param detail one sentence saying what it means
 * @param retryable sending the same command again may work
 * @param authFault the fix is to log in again
 * @param fatal the link should be dropped and the printer flagged
 */
public record StatusInfo(
        Status status, String label, String detail, boolean retryable, boolean authFault, boolean fatal) {}
