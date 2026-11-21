package io.github.rkaushikdev.nozzlewright.storage;

import java.util.List;
import java.util.Optional;

/** The configuration snapshot table. */
public final class SnapshotRepo {

    private final UnitOfWork unit;

    /** The snapshot table, read and written through {@code unit}. */
    public SnapshotRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** Store a configuration as it is now, with no reason and nobody named. */
    public ConfigSnapshot take(Printer printer, String payload, String digest) {
        return take(printer, payload, digest, "", "");
    }

    /** Store a configuration as it is now. */
    public ConfigSnapshot take(Printer printer, String payload, String digest, String reason, String takenBy) {
        ConfigSnapshot snapshot = new ConfigSnapshot(printer.getId(), digest, payload);
        snapshot.setReason(reason);
        snapshot.setTakenBy(takenBy);
        unit.add(snapshot);
        unit.flush();
        return snapshot;
    }

    /** The newest snapshot of one machine. */
    public Optional<ConfigSnapshot> latest(String printerId) {
        return history(printerId, 1).stream().findFirst();
    }

    /** The newest twenty snapshots of one machine, newest first. */
    public List<ConfigSnapshot> history(String printerId) {
        return history(printerId, 20);
    }

    /** Snapshots of one machine, newest first. */
    public List<ConfigSnapshot> history(String printerId, int limit) {
        return unit.entities()
                .createQuery(
                        "select c from ConfigSnapshot c join c.printer p where p.printerId = :id"
                                + " order by c.takenAt desc",
                        ConfigSnapshot.class)
                .setParameter("id", printerId)
                .setMaxResults(limit)
                .getResultList();
    }
}
