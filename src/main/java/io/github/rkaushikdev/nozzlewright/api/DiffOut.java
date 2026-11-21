package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.labels.Change;
import io.github.rkaushikdev.nozzlewright.labels.LabelDiff;
import java.util.List;

/**
 * What changed between two revisions of one label.
 *
 * @param name the label
 * @param left the older revision compared
 * @param right the newer revision compared
 * @param changes every difference
 * @param needsSignoff whether a change needs somebody to sign it off before it prints
 * @param summary one line
 */
public record DiffOut(String name, int left, int right, List<ChangeOut> changes, boolean needsSignoff, String summary) {

    /**
     * One difference between two label revisions.
     *
     * @param kind what kind of change
     * @param subject the element or field it is about
     * @param attribute which setting of it, empty for the whole thing
     * @param before the old value
     * @param after the new value
     * @param significant whether it changes what is printed
     */
    public record ChangeOut(
            String kind, String subject, String attribute, String before, String after, boolean significant) {}

    /** Copies the changes. */
    public DiffOut {
        changes = List.copyOf(changes);
    }

    /** Built from a structural diff. */
    public static DiffOut from(LabelDiff result, int left, int right) {
        List<ChangeOut> rows = result.changes().stream().map(DiffOut::change).toList();
        return new DiffOut(result.name(), left, right, rows, result.needsSignoff(), result.summary());
    }

    private static ChangeOut change(Change change) {
        return new ChangeOut(
                change.kind().value(),
                change.subject(),
                change.attribute(),
                change.before(),
                change.after(),
                change.significant());
    }
}
