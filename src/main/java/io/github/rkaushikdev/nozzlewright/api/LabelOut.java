package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.storage.LabelRevision;
import java.time.Instant;

/**
 * A stored label revision.
 *
 * @param name the label
 * @param revision its revision number, from one
 * @param digest the hash of its source
 * @param heightDots its height
 * @param interactive whether it asks the operator for anything
 * @param createdAt when the revision was stored
 * @param source its source, empty in listings
 */
public record LabelOut(
        String name,
        int revision,
        String digest,
        int heightDots,
        boolean interactive,
        Instant createdAt,
        String source) {

    /** A revision without its source, as listings show it. */
    public static LabelOut from(LabelRevision row) {
        return from(row, false);
    }

    /** A revision, with its source when asked for. */
    public static LabelOut from(LabelRevision row, boolean withSource) {
        return new LabelOut(
                row.getName(),
                row.getRevision(),
                row.getDigest(),
                row.getHeightDots(),
                row.isInteractive(),
                row.getCreatedAt(),
                withSource ? row.getSource() : "");
    }
}
