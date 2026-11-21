package io.github.rkaushikdev.nozzlewright.buildinfo;

import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is actually running on this box.
 *
 * <p>Every support call starts the same way: somebody says the gateway is doing something strange,
 * and the first question is which build it is. A version string alone does not answer it; a site
 * with three boxes on the same version can have three different runtimes and two different
 * database engines. So the version comes with the things that have actually explained a support
 * call: the Java runtime, the H2 version (an H2 older than the 2.0 line cannot open the database
 * files the migrations write), the platform, and the protocol revision this build targets, because
 * that decides whether a verb is even attempted.
 *
 * <p>Nothing here is a secret and nothing identifies a site, so it is safe on an unauthenticated
 * health page.
 *
 * @param version this build
 * @param java the runtime version, {@code 21.0.13}
 * @param implementation the virtual machine, lower case
 * @param h2 the database engine version, {@code 2.3.232}
 * @param platform the operating system and architecture, lower case
 * @param protocolRevision the line protocol revision targeted
 * @param families the machine families supported
 */
public record BuildInfo(
        String version,
        String java,
        String implementation,
        String h2,
        String platform,
        int protocolRevision,
        List<String> families) {

    /** The oldest H2 whose file format the migrations have been run against. */
    public static final List<Integer> MINIMUM_H2 = List.of(2, 0, 202);

    /** Copies the families. */
    public BuildInfo {
        families = List.copyOf(families);
    }

    /**
     * Everything worth knowing about this build, read fresh rather than cached, so a caller can
     * see what a different database version would do with {@link #withH2}.
     */
    public static BuildInfo current() {
        Runtime.Version runtime = Runtime.version();
        return new BuildInfo(
                Nozzlewright.VERSION,
                runtime.feature() + "." + runtime.interim() + "." + runtime.update(),
                System.getProperty("java.vm.name", "java").toLowerCase(Locale.ROOT),
                org.h2.engine.Constants.VERSION,
                (System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""))
                        .toLowerCase(Locale.ROOT)
                        .strip(),
                Nozzlewright.PROTOCOL_REVISION,
                Nozzlewright.SUPPORTED_FAMILIES);
    }

    /** The same build on a different database version. */
    public BuildInfo withH2(String value) {
        return new BuildInfo(version, java, implementation, value, platform, protocolRevision, families);
    }

    /** The H2 version as numbers, for comparing; empty when it cannot be read. */
    public List<Integer> h2Numbers() {
        List<Integer> parts = new ArrayList<>();
        for (String part : h2.split("\\.", -1)) {
            if (!part.matches("\\d+")) {
                return List.of();
            }
            parts.add(Integer.parseInt(part));
        }
        return List.copyOf(parts);
    }

    /** True when the database underneath is new enough for the migrations. */
    public boolean h2Ok() {
        List<Integer> found = h2Numbers();
        if (found.isEmpty()) {
            return false;
        }
        for (int index = 0; index < Math.max(found.size(), MINIMUM_H2.size()); index++) {
            int have = index < found.size() ? found.get(index) : -1;
            int need = index < MINIMUM_H2.size() ? MINIMUM_H2.get(index) : -1;
            if (have != need) {
                return have > need;
            }
        }
        return true;
    }

    /** The block a support engineer asks to be read out. */
    public String render() {
        return String.join(
                "\n",
                "nozzlewright " + version,
                implementation + " " + java + " on " + platform,
                "h2 " + h2 + (h2Ok() ? "" : " (too old)"),
                "protocol revision " + protocolRevision + ", families " + String.join(", ", families));
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("java", java);
        body.put("implementation", implementation);
        body.put("h2", h2);
        body.put("h2_ok", h2Ok());
        body.put("platform", platform);
        body.put("protocol_revision", protocolRevision);
        body.put("families", families);
        return body;
    }
}
