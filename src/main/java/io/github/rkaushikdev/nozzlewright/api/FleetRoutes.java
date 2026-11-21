package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.github.rkaushikdev.nozzlewright.buildinfo.BuildInfo;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.SiteStats;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Times;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site wide routes: health, the wall board and the alarm screen.
 *
 * <p>Everything here reads what the poller and the recorder already collected, so a page refresh
 * costs a query and not forty radio round trips.
 */
@RestController
@Tag(name = "fleet")
public class FleetRoutes {

    private final Services services;

    /** The routes over one services object. */
    public FleetRoutes(Services services) {
        this.services = services;
    }

    /** Liveness, and enough detail to tell a broken gateway from an idle one. Needs no key. */
    @GetMapping("/health")
    public HealthOut health() {
        int count;
        String database;
        try {
            count = services.database()
                    .apply(unit -> new PrinterRepo(unit).all().size());
            database = "up";
        } catch (RuntimeException down) {
            count = 0;
            database = "down";
        }
        KeyRing ring = services.keyring();
        return new HealthOut(
                database.equals("up"),
                Nozzlewright.VERSION,
                services.settings().siteId(),
                count,
                database,
                ring.size(),
                !ring.any(),
                services.poller().isPresent(),
                BuildInfo.current().asMap());
    }

    /**
     * The page on the pillar in the packing hall.
     *
     * <p>Deliberately not behind the API key. A screen on a wall cannot present a header, the page
     * carries no controls and nothing on it is worth taking, and the alternative is a browser
     * configured with a key in its URL bar where every passing phone can photograph it.
     */
    @Hidden
    @GetMapping(value = "/board", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> wallBoard() {
        Map<String, PollResult> snapshot =
                services.poller().map(PollView::snapshot).orElse(Map.of());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(Board.render(services.settings().siteId(), snapshot, Instant.now()));
    }

    /** The last reading of every machine, in order of its id. */
    @RequiresKey
    @GetMapping("/fleet/status")
    public List<StatusOut> fleetStatus() {
        Optional<PollView> poller = services.poller();
        if (poller.isEmpty()) {
            return List.of();
        }
        List<StatusOut> out = new ArrayList<>();
        for (PollResult result : new TreeMap<>(poller.get().snapshot()).values()) {
            out.add(StatusOut.of(result));
        }
        return out;
    }

    /** The rollup a wall board shows. */
    @RequiresKey
    @GetMapping("/fleet/summary")
    public SiteOut fleetSummary() {
        SiteStats site = services.stats().site();
        String worst = Severity.INFO.value();
        Optional<PollView> poller = services.poller();
        if (poller.isPresent()) {
            List<String> severities = poller.get().snapshot().values().stream()
                    .map(result -> result.severity().value())
                    .toList();
            for (Severity level : List.of(Severity.STOP, Severity.WARNING)) {
                if (severities.contains(level.value())) {
                    worst = level.value();
                    break;
                }
            }
        }
        return new SiteOut(
                services.settings().siteId(),
                site.machines(),
                site.printingNow(),
                site.down(),
                site.printed(),
                Numbers.round(site.availability(), 4),
                Numbers.round(site.utilisation(), 4),
                worst);
    }

    /**
     * Every alarm still up, newest first, optionally for one machine.
     *
     * <p>Paged, unlike the machine list. A site has a fixed handful of machines and an unbounded
     * number of things that can be wrong with them, and the day this endpoint matters most is the day
     * a switch went down and every machine on the site raised unreachable at once.
     */
    @RequiresKey
    @GetMapping("/fleet/alarms")
    public Page<AlarmOut> fleetAlarms(
            @RequestParam(name = "printer_id", required = false) String printerId, PageParams paging) {
        Instant now = Times.now();
        return services.database().apply(unit -> {
            List<AlarmRecord> rows =
                    printerId == null ? new AlarmRepo(unit).active() : new AlarmRepo(unit).active(printerId);
            Map<Integer, String> byPk = new HashMap<>();
            for (Printer printer : new PrinterRepo(unit).all()) {
                byPk.put(printer.getId(), printer.getPrinterId());
            }
            List<AlarmOut> out = new ArrayList<>();
            for (AlarmRecord row : rows) {
                out.add(AlarmOut.from(byPk.getOrDefault(row.getPrinterPk(), ""), row, now));
            }
            return Page.ofAll(out, paging);
        });
    }

    /** Record that somebody is dealing with an alarm; nobody named reads as {@code unknown}. */
    @RequiresKey
    @PostMapping("/fleet/alarms/{printer_id}/{name}/ack")
    public AlarmOut acknowledgeAlarm(
            @PathVariable("printer_id") String printerId,
            @PathVariable("name") String name,
            @RequestParam(name = "who", defaultValue = "") String who) {
        return services.database().apply(unit -> {
            Printer printer = new PrinterRepo(unit)
                    .get(printerId)
                    .orElseThrow(() -> new ApiProblem(404, "no machine called " + printerId));
            AlarmRecord row = new AlarmRepo(unit)
                    .acknowledge(printer, name, who.isEmpty() ? "unknown" : who)
                    .orElseThrow(() -> new ApiProblem(404, printerId + " has no open alarm called " + name));
            return AlarmOut.from(printerId, row, Times.now());
        });
    }
}
