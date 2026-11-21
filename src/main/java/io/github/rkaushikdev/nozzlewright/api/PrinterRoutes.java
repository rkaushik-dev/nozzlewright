package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.printer.ApplyReport;
import io.github.rkaushikdev.nozzlewright.printer.HeadHealth;
import io.github.rkaushikdev.nozzlewright.printer.HeadRecovery;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.Snapshot;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SnapshotRepo;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything under {@code /printers}.
 *
 * <p>Reads go to the live machine where the answer has to be current and to the database where it
 * does not, which is the split a handheld on a slow radio actually needs: a wall board refresh should
 * not put forty commands on the wire. A route that talks to the machine needs the poller running
 * (503 when it is not) and a session for that machine (404 when there is none).
 */
@RestController
@RequiresKey
@RequestMapping("/printers")
@Tag(name = "printers")
public class PrinterRoutes {

    private final Services services;

    /** The routes over one services object. */
    public PrinterRoutes(Services services) {
        this.services = services;
    }

    /** Every machine on the site, optionally only one line's, or only the enabled or disabled ones. */
    @GetMapping
    public List<PrinterOut> listPrinters(
            @RequestParam(name = "line", required = false) String line,
            @RequestParam(name = "enabled", required = false) Boolean enabled) {
        return services.database().apply(unit -> {
            Map<String, Printer> rows = new HashMap<>();
            for (Printer row : new PrinterRepo(unit).all()) {
                rows.put(row.getPrinterId(), row);
            }
            List<PrinterRecord> records = line == null
                    ? services.registry().all()
                    : services.registry().onLine(line);
            List<PrinterOut> out = new ArrayList<>();
            for (PrinterRecord record : records) {
                if (enabled == null || record.enabled() == enabled) {
                    out.add(PrinterOut.from(record, Optional.ofNullable(rows.get(record.printerId()))));
                }
            }
            return out;
        });
    }

    /** Register a machine, or update one that is already registered. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PrinterOut addPrinter(@Valid @RequestBody PrinterIn body) {
        return services.database().apply(unit -> {
            PrinterRecord stored = services.registry().add(body.toRecord(), true);
            new PrinterRepo(unit).upsert(stored);
            return PrinterOut.from(stored);
        });
    }

    /** One machine. */
    @GetMapping("/{printer_id}")
    public PrinterOut getPrinter(@PathVariable("printer_id") String printerId) {
        return services.database().apply(unit -> {
            PrinterRecord record = services.registry().get(printerId);
            return PrinterOut.from(record, new PrinterRepo(unit).get(printerId));
        });
    }

    /** Take a machine off the site. */
    @DeleteMapping("/{printer_id}")
    public ResponseEntity<Void> removePrinter(@PathVariable("printer_id") String printerId) {
        services.database().run(unit -> {
            services.registry().remove(printerId);
            PrinterRepo repo = new PrinterRepo(unit);
            if (repo.get(printerId).isPresent()) {
                repo.remove(printerId);
            }
        });
        return ResponseEntity.noContent().build();
    }

    /** The machine status, from the last poll or, with {@code live}, straight off the wire. */
    @GetMapping("/{printer_id}/status")
    public StatusOut printerStatus(
            @PathVariable("printer_id") String printerId,
            @RequestParam(name = "live", defaultValue = "false") boolean live)
            throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        if (!live) {
            Optional<PollResult> cached = services.poller().flatMap(poller -> poller.latest(printerId));
            if (cached.isPresent()) {
                return StatusOut.of(cached.get());
            }
        }
        return StatusOut.from(printerId, Operations.readStatus(session));
    }

    /** Ink levels, always read live because they move. */
    @GetMapping("/{printer_id}/ink")
    public List<InkOut> printerInk(@PathVariable("printer_id") String printerId) throws InterruptedException {
        return Operations.readInk(sessionFor(printerId)).stream()
                .map(InkOut::from)
                .toList();
    }

    /**
     * Run a nozzle test on the machine and decode it; pen nought tests every cartridge.
     *
     * <p>A test stops the print for as long as it takes to fire every nozzle once, which is why this
     * is a GET that a wall board must not poll. Nothing caches it either, because a cached nozzle test
     * is a lie about a head that has been purged since.
     */
    @GetMapping("/{printer_id}/nozzles")
    public NozzleOut printerNozzles(
            @PathVariable("printer_id") String printerId, @RequestParam(name = "pen", defaultValue = "0") int pen)
            throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        QueryValues.within("pen", pen, 0, 8);
        HeadHealth health = Operations.readNozzles(session, pen);
        return NozzleOut.from(printerId, health);
    }

    /** Purge and wipe whichever cartridge the nozzle test says needs it; pen nought means the worst. */
    @PostMapping("/{printer_id}/recover")
    public RecoveryOut printerRecover(
            @PathVariable("printer_id") String printerId,
            @RequestParam(name = "pen", defaultValue = "0") int pen,
            @RequestParam(name = "drops", defaultValue = "200") int drops)
            throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        QueryValues.within("pen", pen, 0, 8);
        QueryValues.within("drops", drops, 1, 2000);
        HeadRecovery recovery = Operations.recoverHead(session, pen, drops, true);
        HeadHealth before = recovery.before();
        if (recovery.after().isEmpty()) {
            return new RecoveryOut(
                    printerId, before.dead(), before.dead(), 0, before.advice().value(), false);
        }
        HeadHealth after = recovery.after().get();
        return new RecoveryOut(
                printerId,
                before.dead(),
                after.dead(),
                before.dead() - after.dead(),
                after.advice().value(),
                true);
    }

    /** Read the whole configuration and keep a snapshot of it. */
    @GetMapping("/{printer_id}/config")
    public ConfigOut printerConfig(@PathVariable("printer_id") String printerId) throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        PrinterConfig config = Operations.readConfig(session);
        String payload = Snapshot.renderConfigTable(config);
        services.database().run(unit -> new PrinterRepo(unit).get(printerId).ifPresent(row -> new SnapshotRepo(unit)
                .take(row, payload, LabelStore.contentHash(payload), "api read", "")));
        return ConfigOut.from(printerId, config, payload);
    }

    /** Push a configuration onto a machine. */
    @PostMapping("/{printer_id}/config")
    public ApplyOut applyPrinterConfig(@PathVariable("printer_id") String printerId, @Valid @RequestBody ApplyIn body)
            throws InterruptedException {
        PrinterSession session = sessionFor(printerId);
        Dialect dialect =
                session.dialect().orElseThrow(() -> new ApiProblem(503, "the machine has not identified itself yet"));
        PrinterConfig target = Snapshot.parseConfigTable(Codec.parseTable(body.payload()), dialect.family());
        ApplyReport report = Operations.applyConfig(session, target, body.dryRun(), body.stopFirst());
        return ApplyOut.from(printerId, report);
    }

    /** Start the machine. */
    @PostMapping("/{printer_id}/print/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> startPrint(@PathVariable("printer_id") String printerId) throws InterruptedException {
        sessionFor(printerId).command("STARTPRINT");
        return Map.of("state", "starting");
    }

    /** Stop the machine. */
    @PostMapping("/{printer_id}/print/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> stopPrint(@PathVariable("printer_id") String printerId) throws InterruptedException {
        sessionFor(printerId).command("STOPPRINT");
        return Map.of("state", "stopping");
    }

    /** Make a stored label the active one. */
    @PostMapping("/{printer_id}/label/{name}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> selectLabel(
            @PathVariable("printer_id") String printerId, @PathVariable("name") String name)
            throws InterruptedException {
        sessionFor(printerId).command("LABEL", name);
        return Map.of("label", name);
    }

    /**
     * The live session for the machine named in the path.
     *
     * @throws ApiProblem with status 503 when the poller is not running, 404 when it has no session for
     *     the machine
     */
    private PrinterSession sessionFor(String printerId) {
        try {
            return services.sessionFor(printerId);
        } catch (NozzlewrightError error) {
            throw new ApiProblem(404, String.valueOf(error.getMessage()));
        }
    }
}
