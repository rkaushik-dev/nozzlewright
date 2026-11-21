package io.github.rkaushikdev.nozzlewright;

import java.util.List;

/** What this build is and which machines it talks to. */
public final class Nozzlewright {

    /** The version of this build. */
    public static final String VERSION = "0.1.0";

    /**
     * Revision of the Halvex line protocol this build targets. The printers report their own
     * revision in the VERSION response and anything newer is refused.
     */
    public static final int PROTOCOL_REVISION = 2;

    /**
     * Machine families the controller knows how to talk to. Anything else is rejected at session
     * setup rather than half way through a print run.
     */
    public static final List<String> SUPPORTED_FAMILIES = List.of("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP");

    private Nozzlewright() {}
}
