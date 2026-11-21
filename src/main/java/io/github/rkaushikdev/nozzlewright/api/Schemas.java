package io.github.rkaushikdev.nozzlewright.api;

/**
 * What the request and answer records share.
 *
 * <p>The wire records are separate from the domain objects on purpose. The domain model changes when
 * a machine changes and the wire model changes when a client changes, and tying them together turns a
 * new pen offset field into a breaking API release. Each answer record has one {@code from} that knows
 * how its domain object becomes JSON. Field names are the ones the handheld app already uses.
 */
final class Schemas {

    /** What a request is told when it leaves out a field it needs. */
    static final String REQUIRED = "Field required";

    private Schemas() {}
}
