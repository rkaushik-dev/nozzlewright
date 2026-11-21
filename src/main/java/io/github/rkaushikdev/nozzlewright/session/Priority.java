package io.github.rkaushikdev.nozzlewright.session;

import java.util.Map;

/**
 * How badly a command wants to go next.
 *
 * <p>A radio that manages eight commands a second and a wall board that refreshes forty machines
 * will fill the queue, and when it does it matters a great deal which commands are lost. Stopping a
 * line is not allowed to queue behind a status poll that nobody is waiting for.
 */
public enum Priority {
    ROUTINE,
    NORMAL,
    URGENT;

    /** Verbs that do not get the default priority. Everything else is {@link #NORMAL}. */
    public static final Map<String, Priority> PRIORITY_FOR = Map.of(
            "STOPPRINT", URGENT,
            "PAUSEPRINT", URGENT,
            "LOGOUT", URGENT,
            "STATUS", ROUTINE,
            "INK", ROUTINE,
            "INK2", ROUTINE,
            "STATISTICS", ROUTINE,
            "PING", ROUTINE);

    /** How urgent one verb is. */
    public static Priority of(String verb) {
        return PRIORITY_FOR.getOrDefault(verb, NORMAL);
    }
}
