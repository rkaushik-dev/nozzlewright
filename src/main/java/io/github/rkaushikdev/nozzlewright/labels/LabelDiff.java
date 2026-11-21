package io.github.rkaushikdev.nozzlewright.labels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * Every difference between two revisions of one label.
 *
 * <p>A label revision is stored as source text, so the obvious answer is a line diff, and the
 * obvious answer is wrong. Reordering two elements changes every line and changes nothing about
 * what prints. Moving a barcode two dots left changes one line and can put it off the edge of the
 * carton. So this compares the parsed structures instead, and sorts what it finds into two heaps:
 * changes that alter what a scanner reads or where a mark lands are significant, and a change to a
 * font on a human readable line under a barcode is not. Somebody signing off a recipe change wants
 * the first heap on one screen and the second folded away.
 *
 * <p>Field changes carry more weight than element changes, because a field is what the operator is
 * asked for and what the counter counts. Changing a counter start mid production run is the kind of
 * thing that gets found three days later on a pallet nobody can ship.
 *
 * @param name the label name, as the newer revision has it
 * @param changes every difference: the height first, then elements, then fields
 */
public record LabelDiff(String name, List<Change> changes) {

    /** Copies the changes. */
    public LabelDiff {
        changes = List.copyOf(changes);
    }

    /**
     * Compare two revisions of a message.
     *
     * <p>The two are matched by name, element by element and field by field, so the order they are
     * stored in never shows up as a change. Within each heap, added names come first, then removed
     * ones, then the ones both have, each in name order. An element whose name was changed reads as
     * one removed and one added, which is the honest answer: nothing can tell a rename from a
     * replacement without being told.
     */
    public static LabelDiff between(LabelTemplate before, LabelTemplate after) {
        List<Change> changes = new ArrayList<>();
        if (before.heightDots() != after.heightDots()) {
            changes.add(new Change(
                    ChangeKind.HEIGHT_CHANGED,
                    after.name(),
                    "height_dots",
                    String.valueOf(before.heightDots()),
                    String.valueOf(after.heightDots())));
        }
        changes.addAll(elementChanges(before, after));
        changes.addAll(fieldChanges(before, after));
        return new LabelDiff(after.name(), changes);
    }

    /** True when anything at all changed. */
    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    /** The changes that need somebody to look at them. */
    public List<Change> significant() {
        return changes.stream().filter(Change::significant).toList();
    }

    /** The changes that do not. */
    public List<Change> cosmetic() {
        return changes.stream().filter(change -> !change.significant()).toList();
    }

    /** True when a quality person has to approve this. */
    public boolean needsSignoff() {
        return !significant().isEmpty();
    }

    /** Every change of one kind. */
    public List<Change> ofKind(ChangeKind kind) {
        return changes.stream().filter(change -> change.kind() == kind).toList();
    }

    /** One line for the console: {@code no change}, or {@code 2 changes, 1 of them significant}. */
    public String summary() {
        if (changes.isEmpty()) {
            return "no change";
        }
        return changes.size() + " changes, " + significant().size() + " of them significant";
    }

    /** One mapping per change, for the console and the API. */
    public List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Change change : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", change.kind().value());
            row.put("subject", change.subject());
            row.put("attribute", change.attribute());
            row.put("before", change.before());
            row.put("after", change.after());
            row.put("significant", change.significant());
            rows.add(row);
        }
        return rows;
    }

    /** Added, removed, moved, retyped and edited elements. */
    private static List<Change> elementChanges(LabelTemplate before, LabelTemplate after) {
        Map<String, Element> was = new LinkedHashMap<>();
        before.elements().forEach(item -> was.put(item.name(), item));
        Map<String, Element> now = new LinkedHashMap<>();
        after.elements().forEach(item -> now.put(item.name(), item));
        List<Change> changes = new ArrayList<>();

        for (String name : onlyIn(now, was)) {
            changes.add(new Change(
                    ChangeKind.ELEMENT_ADDED, name, "", "", now.get(name).kind()));
        }
        for (String name : onlyIn(was, now)) {
            changes.add(new Change(
                    ChangeKind.ELEMENT_REMOVED, name, "", was.get(name).kind(), ""));
        }
        for (String name : inBoth(was, now)) {
            Element old = was.get(name);
            Element current = now.get(name);
            if (old.getClass() != current.getClass()) {
                changes.add(new Change(ChangeKind.ELEMENT_RETYPED, name, "", old.kind(), current.kind()));
                continue;
            }
            if (!old.placement().equals(current.placement())) {
                changes.add(new Change(
                        ChangeKind.ELEMENT_MOVED,
                        name,
                        "placement",
                        old.placement().toSource(),
                        current.placement().toSource()));
            }
            changes.addAll(
                    attributeChanges(ChangeKind.ELEMENT_CHANGED, name, attributesOf(old), attributesOf(current)));
        }
        return changes;
    }

    /** Added, removed and edited fields. */
    private static List<Change> fieldChanges(LabelTemplate before, LabelTemplate after) {
        Map<String, Field> was = new LinkedHashMap<>();
        before.fields().forEach(item -> was.put(item.name(), item));
        Map<String, Field> now = new LinkedHashMap<>();
        after.fields().forEach(item -> now.put(item.name(), item));
        List<Change> changes = new ArrayList<>();

        for (String name : onlyIn(now, was)) {
            changes.add(new Change(
                    ChangeKind.FIELD_ADDED, name, "", "", now.get(name).kind()));
        }
        for (String name : onlyIn(was, now)) {
            changes.add(
                    new Change(ChangeKind.FIELD_REMOVED, name, "", was.get(name).kind(), ""));
        }
        for (String name : inBoth(was, now)) {
            changes.addAll(attributeChanges(
                    ChangeKind.FIELD_CHANGED,
                    name,
                    was.get(name).attributes(),
                    now.get(name).attributes()));
        }
        return changes;
    }

    /**
     * One change per setting of the older revision whose value moved.
     *
     * <p>A setting the newer revision lacks, because the field changed kind, reads as empty.
     */
    private static List<Change> attributeChanges(
            ChangeKind kind, String name, Map<String, Object> was, Map<String, Object> now) {
        List<Change> changes = new ArrayList<>();
        was.forEach((attribute, old) -> {
            Object current = now.get(attribute);
            if (!Objects.equals(old, current)) {
                changes.add(new Change(kind, name, attribute, text(old), text(current)));
            }
        });
        return changes;
    }

    /**
     * The settings of an element a diff looks at, other than its placement.
     *
     * <p>A Code 39 check character and wide ratio are left out, as is everything about an element of
     * a kind other than text, barcode and rule.
     */
    private static Map<String, Object> attributesOf(Element element) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        switch (element) {
            case TextElement text -> {
                attributes.put("source", text.source());
                attributes.put("font", text.font());
                attributes.put("invert", text.invert());
                attributes.put("reverse", text.reverse());
            }
            case BarcodeElement barcode -> {
                attributes.put("source", barcode.source());
                attributes.put("symbology", barcode.symbology());
                attributes.put("height_dots", barcode.heightDots());
                attributes.put("module_dots", barcode.moduleDots());
                attributes.put("human_readable", barcode.humanReadable());
                attributes.put("text_font", barcode.textFont());
            }
            case RuleElement rule -> {
                attributes.put("width_dots", rule.widthDots());
                attributes.put("height_dots", rule.heightDots());
            }
            default -> {
                // Nothing else is compared beyond its placement and kind.
            }
        }
        return attributes;
    }

    /** One setting as a person reads it: a flag as yes or no, nothing as empty. */
    private static String text(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        if (value == null) {
            return "";
        }
        if (value instanceof OptionalLong optional) {
            return optional.isPresent() ? Long.toString(optional.getAsLong()) : "";
        }
        return String.valueOf(value);
    }

    private static TreeSet<String> onlyIn(Map<String, ?> these, Map<String, ?> others) {
        TreeSet<String> names = new TreeSet<>(these.keySet());
        names.removeAll(others.keySet());
        return names;
    }

    private static TreeSet<String> inBoth(Map<String, ?> these, Map<String, ?> others) {
        TreeSet<String> names = new TreeSet<>(these.keySet());
        names.retainAll(others.keySet());
        return names;
    }
}
