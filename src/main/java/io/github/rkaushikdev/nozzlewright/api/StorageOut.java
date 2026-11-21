package io.github.rkaushikdev.nozzlewright.api;

/**
 * How big the database is and how much of it is free space.
 *
 * @param bytes the size of the database file
 * @param pages the file size in pages
 * @param freePages the pages holding nothing
 * @param worthVacuuming true when a vacuum would give a useful amount back
 */
public record StorageOut(long bytes, long pages, long freePages, boolean worthVacuuming) {

    /** Nothing to report, for a database that has no file to measure. */
    public static final StorageOut NOTHING = new StorageOut(0, 0, 0, false);
}
