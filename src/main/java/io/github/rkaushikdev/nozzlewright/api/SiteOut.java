package io.github.rkaushikdev.nozzlewright.api;

/**
 * The site rollup a wall board asks for.
 *
 * @param siteId the site
 * @param machines machines with a window open
 * @param printing machines coding at their last poll
 * @param down machines that did not answer their last poll
 * @param printed products coded across every window
 * @param availability fraction of readings answered, to four places
 * @param utilisation fraction of readings coding, to four places
 * @param worstSeverity the worst severity among the last readings
 */
public record SiteOut(
        String siteId,
        int machines,
        int printing,
        int down,
        long printed,
        double availability,
        double utilisation,
        String worstSeverity) {}
