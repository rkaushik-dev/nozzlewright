package io.github.rkaushikdev.nozzlewright.api;

import java.util.List;

/**
 * What a firmware upgrade would add on one family.
 *
 * @param family the family, upper case
 * @param current the revision the machine runs now
 * @param target the newest revision this build knows
 * @param gains the verbs the upgrade would add
 */
public record UpgradeOut(String family, int current, int target, List<String> gains) {

    /** Copies the verbs. */
    public UpgradeOut {
        gains = List.copyOf(gains);
    }
}
