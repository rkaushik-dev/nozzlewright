package io.github.rkaushikdev.nozzlewright.storage;

import java.util.List;
import java.util.Optional;

/** The label revision table: every version of every label pushed to a machine. */
public final class LabelRepo {

    private final UnitOfWork unit;

    /** The label revision table, read and written through {@code unit}. */
    public LabelRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** The newest revision of one label. */
    public Optional<LabelRevision> latest(String name) {
        return unit.entities()
                .createQuery(
                        "select l from LabelRevision l where l.name = :name order by l.revision desc",
                        LabelRevision.class)
                .setParameter("name", name)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** Every revision of one label, oldest first. */
    public List<LabelRevision> revisions(String name) {
        return unit.entities()
                .createQuery(
                        "select l from LabelRevision l where l.name = :name order by l.revision", LabelRevision.class)
                .setParameter("name", name)
                .getResultList();
    }

    /** Every label name, sorted. */
    public List<String> names() {
        return unit.entities()
                .createQuery("select distinct l.name from LabelRevision l order by l.name", String.class)
                .getResultList();
    }

    /** Store a revision of no stated height, not interactive, by nobody in particular. */
    public LabelRevision save(String name, String source, String digest) {
        return save(name, source, digest, 0, false, "");
    }

    /**
     * Store a revision, or hand back the current one when nothing changed.
     *
     * <p>A label whose digest matches its newest revision is the same label, so no row is written
     * and the newest revision comes back as it was. Anything else becomes the next revision, the
     * first being revision one.
     */
    public LabelRevision save(
            String name, String source, String digest, int heightDots, boolean interactive, String createdBy) {
        Optional<LabelRevision> current = latest(name);
        if (current.isPresent() && current.get().getDigest().equals(digest)) {
            return current.get();
        }
        int number = current.map(found -> found.getRevision() + 1).orElse(1);
        LabelRevision revision = new LabelRevision(name, number, digest, source);
        revision.setHeightDots(heightDots);
        revision.setInteractive(interactive);
        revision.setCreatedBy(createdBy);
        unit.add(revision);
        unit.flush();
        return revision;
    }
}
