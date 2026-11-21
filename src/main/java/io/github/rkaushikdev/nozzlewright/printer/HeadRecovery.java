package io.github.rkaushikdev.nozzlewright.printer;

import java.util.Optional;

/**
 * A head recovery: the nozzle test it started from and, when it purged and looked again, the one
 * after, so the caller can say whether the purge helped.
 *
 * @param before the reading that decided what to do
 * @param after the reading once the purge was done, empty when nothing was done or no retest was
 *     asked for
 */
public record HeadRecovery(HeadHealth before, Optional<HeadHealth> after) {}
