package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Following cartridges around a site: the fitting table.
 *
 * <p>INK2 gives a serial per pen as well as a level, which is the only thing on the machine that
 * identifies a cartridge. Folding those readings into fitting rows is what turns "pen two is at forty
 * percent" into "the cartridge that printed Tuesday afternoon went in on Monday and came out on
 * Friday". The rules each come from something that went wrong once:
 *
 * <ul>
 *   <li>A serial that appears in a pen opens a fitting.
 *   <li>The same serial reading again updates it.
 *   <li>A different serial closes the old fitting and opens a new one.
 *   <li>A pen that reports no serial, or stops reporting at all, closes whatever was there, because
 *       a cartridge that has been pulled is not still printing.
 *   <li>A level that goes up on the same serial is a refill. It is recorded rather than argued with,
 *       and the first level is reset so the usage figure stays honest.
 * </ul>
 */
public final class CartridgeRepo {

    /**
     * What one INK2 reading did.
     *
     * @param opened fittings opened
     * @param closed fittings closed
     */
    public record Observed(int opened, int closed) {}

    private final UnitOfWork unit;

    /** The fitting table, read and written through {@code unit}. */
    public CartridgeRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** What is in one pen right now, as far as the readings say. */
    public Optional<CartridgeFitting> fitted(Printer printer, int pen) {
        return unit.entities()
                .createQuery(
                        "select f from CartridgeFitting f where f.printerPk = :pk and f.pen = :pen"
                                + " and f.removedAt is null order by f.id",
                        CartridgeFitting.class)
                .setParameter("pk", printer.getId())
                .setParameter("pen", pen)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** Everything currently in the machine, by pen. */
    public List<CartridgeFitting> openFittings(Printer printer) {
        return unit.entities()
                .createQuery(
                        "select f from CartridgeFitting f where f.printerPk = :pk and f.removedAt is null"
                                + " order by f.pen",
                        CartridgeFitting.class)
                .setParameter("pk", printer.getId())
                .getResultList();
    }

    /** Everything in every machine on the site, by fitting. */
    public List<CartridgeFitting> allOpen() {
        return unit.entities()
                .createQuery(
                        "select f from CartridgeFitting f where f.removedAt is null order by f.printerPk, f.pen",
                        CartridgeFitting.class)
                .getResultList();
    }

    /** Everywhere one cartridge has been, oldest fitting first. */
    public List<CartridgeFitting> history(String serial) {
        return unit.entities()
                .createQuery(
                        "select f from CartridgeFitting f where f.serial = :serial order by f.fittedAt, f.id",
                        CartridgeFitting.class)
                .setParameter("serial", serial)
                .getResultList();
    }

    /** Fold one INK2 reading in, stamped now. */
    public Observed observe(Printer printer, List<InkReading> readings) {
        return observe(printer, readings, Times.now());
    }

    /** Fold one INK2 reading in, stamped {@code at}. */
    public Observed observe(Printer printer, List<InkReading> readings, Instant at) {
        int opened = 0;
        int closed = 0;
        Set<Integer> seenPens = new HashSet<>();

        for (InkReading reading : readings) {
            seenPens.add(reading.pen());
            Optional<CartridgeFitting> current = fitted(printer, reading.pen());
            if (!reading.identified()) {
                if (current.isPresent()) {
                    current.get().setRemovedAt(at);
                    closed++;
                }
                continue;
            }
            if (current.isEmpty()) {
                open(printer, reading, at);
                opened++;
                continue;
            }
            CartridgeFitting row = current.get();
            if (!row.getSerial().equals(reading.serial())) {
                row.setRemovedAt(at);
                closed++;
                open(printer, reading, at);
                opened++;
                continue;
            }
            update(row, reading);
        }

        for (CartridgeFitting stale : openFittings(printer)) {
            if (!seenPens.contains(stale.getPen())) {
                stale.setRemovedAt(at);
                closed++;
            }
        }

        unit.flush();
        return new Observed(opened, closed);
    }

    private void open(Printer printer, InkReading reading, Instant at) {
        CartridgeFitting row = new CartridgeFitting(printer.getId(), reading.pen(), reading.serial());
        row.setPartNumber(reading.partNumber());
        row.setInkFamily(reading.inkFamily());
        row.setFittedAt(at);
        row.setFirstLevel(reading.level());
        row.setLastLevel(reading.level());
        row.setReadings(1);
        unit.add(row);
    }

    private static void update(CartridgeFitting row, InkReading reading) {
        if (reading.level() > row.getLastLevel()) {
            // A refill, or a machine topped up between readings. Reset the baseline or the usage
            // figure runs negative for the rest of the cartridge life.
            row.setFirstLevel(reading.level());
        }
        row.setLastLevel(reading.level());
        row.setReadings(row.getReadings() + 1);
    }
}
