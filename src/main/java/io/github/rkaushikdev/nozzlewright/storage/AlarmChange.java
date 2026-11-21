package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.printer.Severity;

/**
 * The state of one alarm on one machine, as of one reading.
 *
 * <p>Reported for every alarm the reading shows and for every one it stopped showing, not only for
 * the ones that changed. Whoever is deciding whether to tell a person needs to see the repeats too,
 * because an alarm nobody has acknowledged for twenty minutes is worth saying again and that is not
 * visible from a single transition.
 *
 * @param printerId the machine
 * @param name the alarm name
 * @param severity how bad it is
 * @param openNow true while the reading still shows it, false for the reading that cleared it
 * @param acknowledged whether somebody has taken it on
 */
public record AlarmChange(String printerId, String name, Severity severity, boolean openNow, boolean acknowledged) {}
