package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.AuditEntry;
import io.github.rkaushikdev.nozzlewright.storage.AuditLog;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import io.github.rkaushikdev.nozzlewright.util.Times;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything under {@code /history}: reading back what was written down.
 *
 * <p>These are the endpoints a quality question hits three days after the fact, so they read the
 * database and never the machine, and they all page. A listing for a machine nobody has heard of is a
 * 404 rather than an empty page.
 */
@RestController
@RequiresKey
@RequestMapping("/history")
@Tag(name = "history")
public class HistoryRoutes {

    private final Services services;

    /** The routes over one services object. */
    public HistoryRoutes(Services services) {
        this.services = services;
    }

    /** Poll readings for one machine inside a window, oldest first. */
    @GetMapping("/samples/{printer_id}")
    public Page<SampleOut> listSamples(@PathVariable("printer_id") String printerId, Window span, PageParams paging) {
        return services.database().apply(unit -> {
            require(unit, printerId);
            List<SampleOut> rows = new SampleRepo(unit)
                    .between(printerId, span.since(), span.until()).stream()
                            .map(row -> SampleOut.from(printerId, row))
                            .toList();
            return Page.ofAll(rows, paging);
        });
    }

    /** Print runs on one machine, newest first. */
    @GetMapping("/runs/{printer_id}")
    public Page<RunOut> listRuns(@PathVariable("printer_id") String printerId, PageParams paging) {
        return services.database().apply(unit -> {
            require(unit, printerId);
            List<RunOut> rows = new JobRepo(unit)
                    .recent(printerId, paging.stop()).stream()
                            .map(row -> RunOut.from(printerId, row))
                            .toList();
            return Page.ofAll(rows, paging);
        });
    }

    /** Alarms that have cleared on one machine, newest first. */
    @GetMapping("/alarms/{printer_id}")
    public Page<AlarmOut> listAlarmHistory(@PathVariable("printer_id") String printerId, PageParams paging) {
        Instant now = Times.now();
        return services.database().apply(unit -> {
            require(unit, printerId);
            List<AlarmOut> rows = new AlarmRepo(unit)
                    .history(printerId, paging.stop()).stream()
                            .map(row -> AlarmOut.from(printerId, row, now))
                            .toList();
            return Page.ofAll(rows, paging);
        });
    }

    /** The audit trail, newest first, narrowed to one machine, else one actor, else one action. */
    @GetMapping("/audit")
    public Page<AuditOut> listAudit(
            @RequestParam(name = "printer_id", required = false) String printerId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "action", required = false) String action,
            PageParams paging) {
        return services.database().apply(unit -> {
            AuditLog log = new AuditLog(unit);
            List<AuditEntry> rows;
            if (printerId != null && !printerId.isEmpty()) {
                rows = log.forPrinter(printerId, paging.stop());
            } else if (actor != null && !actor.isEmpty()) {
                rows = log.byActor(actor, paging.stop());
            } else if (action != null && !action.isEmpty()) {
                rows = log.ofAction(action, paging.stop());
            } else {
                rows = log.recent(paging.stop());
            }
            return Page.ofAll(rows.stream().map(AuditOut::from).toList(), paging);
        });
    }

    /** Refuse a history request for a machine nobody has heard of. */
    private static void require(UnitOfWork unit, String printerId) {
        if (new PrinterRepo(unit).get(printerId).isEmpty()) {
            throw new ApiProblem(404, "no machine called " + printerId);
        }
    }
}
