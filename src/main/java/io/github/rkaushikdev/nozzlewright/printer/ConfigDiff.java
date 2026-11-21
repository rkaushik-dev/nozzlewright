package io.github.rkaushikdev.nozzlewright.printer;

import java.util.List;

/**
 * Everything that differs between two configurations, and how to close the gap.
 *
 * <p>The changes are in the order the commands have to go out: geometry before firing, firing
 * before the trigger, the trigger before the print mode.
 *
 * @param changes every difference, in command order
 */
public record ConfigDiff(List<FieldChange> changes) {

    /** One command of the list that applies a diff. */
    public record Command(String verb, List<Object> args) {

        /** Copies the arguments. */
        public Command {
            args = List.copyOf(args);
        }
    }

    /** No differences. */
    public static final ConfigDiff EMPTY = new ConfigDiff(List.of());

    /** Copies the changes. */
    public ConfigDiff {
        changes = List.copyOf(changes);
    }

    /** True when the two configurations are the same. */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** How many settings differ. */
    public int size() {
        return changes.size();
    }

    /** Just the setting names, for a summary line. */
    public List<String> paths() {
        return changes.stream().map(FieldChange::path).toList();
    }

    /** The command list that applies this diff, in order. */
    public List<Command> commands() {
        return changes.stream()
                .map(change -> new Command(change.verb(), change.args()))
                .toList();
    }
}
