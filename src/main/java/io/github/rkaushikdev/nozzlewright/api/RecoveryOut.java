package io.github.rkaushikdev.nozzlewright.api;

/**
 * What a purge and wipe achieved.
 *
 * @param printerId the machine
 * @param before dead nozzles before
 * @param after dead nozzles after, the same as before when nothing was done
 * @param recovered nozzles brought back
 * @param advice what the head needs now
 * @param retested whether the head was tested again afterwards
 */
public record RecoveryOut(String printerId, int before, int after, int recovered, String advice, boolean retested) {}
