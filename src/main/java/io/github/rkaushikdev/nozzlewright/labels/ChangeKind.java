package io.github.rkaushikdev.nozzlewright.labels;

/** What sort of thing changed between two revisions of a label. */
public enum ChangeKind {
    /** An element only the newer revision has. */
    ELEMENT_ADDED("element.added"),
    /** An element only the older revision has. */
    ELEMENT_REMOVED("element.removed"),
    /** An element that sits somewhere else. */
    ELEMENT_MOVED("element.moved"),
    /** An element name that now belongs to another kind of element. */
    ELEMENT_RETYPED("element.retyped"),
    /** One setting of an element. */
    ELEMENT_CHANGED("element.changed"),
    /** A field only the newer revision has. */
    FIELD_ADDED("field.added"),
    /** A field only the older revision has. */
    FIELD_REMOVED("field.removed"),
    /** One setting of a field. */
    FIELD_CHANGED("field.changed"),
    /** The label height. */
    HEIGHT_CHANGED("height.changed");

    private final String value;

    ChangeKind(String value) {
        this.value = value;
    }

    /** The word the console and the API print. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
