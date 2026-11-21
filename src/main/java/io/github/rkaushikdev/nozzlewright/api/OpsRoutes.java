package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.doctor.Doctor;
import io.github.rkaushikdev.nozzlewright.protocol.Coverage;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Janitor;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything under {@code /ops}: what somebody needs when a site phones up.
 *
 * <p>It is the same information the console gives, over HTTP, because half the time the person who
 * can help is not the person who can get a shell on the box. A support engineer with the API key and a
 * browser can answer most of a support call without anybody driving anywhere.
 *
 * <p>Nothing here changes anything. These are all reads, they are all behind the API key, and the two
 * that could be expensive say what they cost in their own description rather than being quietly rate
 * limited into uselessness.
 */
@RestController
@RequiresKey
@RequestMapping("/ops")
@Tag(name = "ops")
public class OpsRoutes {

    /**
     * Under this fraction of the file being free pages, a vacuum is not worth the minute it takes on a
     * slow card.
     */
    public static final double VACUUM_THRESHOLD = 0.25;

    private final Services services;

    /** The routes over one services object. */
    public OpsRoutes(Services services) {
        this.services = services;
    }

    /**
     * Run the same checks the console does, against the site database in the state directory.
     *
     * <p>Reads the disk and parses every stored label, so it takes a moment on a site with a lot of
     * them. Not something to put on a dashboard refresh.
     */
    @GetMapping("/doctor")
    public DoctorOut doctor() {
        Path stateDir = services.settings().stateDir();
        String url = Database.fileUrl(stateDir.resolve("site"));
        return DoctorOut.from(Doctor.runChecks(stateDir, url));
    }

    /** Which verbs each machine family carries, at one firmware revision; the newest by default. */
    @GetMapping("/capabilities")
    public CapabilityOut capabilities(
            @RequestParam(name = "revision", defaultValue = "" + Dialect.HIGHEST_KNOWN_REVISION) int revision) {
        QueryValues.within("revision", revision, 1, Dialect.HIGHEST_KNOWN_REVISION);
        List<String> families = Dialect.KNOWN_FAMILIES;
        Map<String, Map<String, Boolean>> matrix = Coverage.capabilityMatrix(families, revision);
        Map<String, Map<String, Boolean>> differing = new LinkedHashMap<>();
        for (String verb : Coverage.differingVerbs(families, revision)) {
            differing.put(verb, matrix.get(verb));
        }
        return new CapabilityOut(revision, families, differing);
    }

    /** What a firmware upgrade from {@code current} to the newest revision would buy on one family. */
    @GetMapping("/upgrade/{family}")
    public UpgradeOut upgrade(
            @PathVariable("family") String family, @RequestParam(name = "current", defaultValue = "1") int current) {
        QueryValues.within("current", current, 1, Integer.MAX_VALUE);
        String wanted = family.toUpperCase(Locale.ROOT);
        if (!Dialect.KNOWN_FAMILIES.contains(wanted)) {
            throw new ApiProblem(404, "no machine family called " + family);
        }
        return new UpgradeOut(wanted, current, Dialect.HIGHEST_KNOWN_REVISION, Coverage.upgradeGains(wanted, current));
    }

    /**
     * How much of the card the history is using.
     *
     * <p>The free page count is the interesting number. A file that is mostly free pages has had a big
     * sweep and has not been compacted since, which is the one case where rewriting it is worth the
     * minute it takes. A database with no file to measure reports nothing.
     */
    @GetMapping("/storage")
    public StorageOut storage() {
        Map<String, Long> stats =
                Janitor.builder(services.database()).compacts(true).build().pageStats();
        if (stats.isEmpty()) {
            return StorageOut.NOTHING;
        }
        long pages = stats.getOrDefault("pages", 0L);
        long free = stats.getOrDefault("free_pages", 0L);
        return new StorageOut(
                stats.getOrDefault("bytes", 0L), pages, free, pages != 0 && (double) free / pages >= VACUUM_THRESHOLD);
    }
}
