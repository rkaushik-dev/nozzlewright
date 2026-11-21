package io.github.rkaushikdev.nozzlewright.fleet;

/**
 * The whole site rolled up.
 *
 * @param machines machines with a window open
 * @param reachableNow machines that answered their last poll
 * @param printingNow machines that were coding at their last poll
 * @param printed products coded across every window
 * @param availability fraction of every reading that got an answer
 * @param utilisation fraction of every reading where the machine was coding
 */
public record SiteStats(
        int machines, int reachableNow, int printingNow, long printed, double availability, double utilisation) {

    /** A site nobody has polled. */
    public static final SiteStats EMPTY = new SiteStats(0, 0, 0, 0, 0.0, 0.0);

    /** Machines that did not answer the last poll. */
    public int down() {
        return machines - reachableNow;
    }

    /** One line for a wall board. */
    public String summary() {
        return machines + " machines, " + printingNow + " printing, " + down() + " down, " + printed + " coded";
    }
}
