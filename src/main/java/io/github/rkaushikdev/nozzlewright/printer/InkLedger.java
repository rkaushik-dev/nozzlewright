package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;

/**
 * Drops thrown by one cartridge, split by why.
 *
 * <p>A TIJ cartridge has no float and no window. The only way to know how much ink is left is to
 * count what has been thrown and multiply by the drop volume. Purges are counted separately and shown
 * separately, otherwise the maintenance habit of purging at every stoppage looks like a leak. Not
 * thread safe: one owner records into it.
 */
public final class InkLedger {

    static final double PICOLITRES_PER_ML = 1e9;

    private long printedDrops;
    private long purgeDrops;

    /** An empty ledger. */
    public InkLedger() {
        this(0, 0);
    }

    /** A ledger that has already counted these drops. */
    public InkLedger(long printedDrops, long purgeDrops) {
        this.printedDrops = printedDrops;
        this.purgeDrops = purgeDrops;
    }

    /** Drops thrown printing. */
    public long printedDrops() {
        return printedDrops;
    }

    /** Drops thrown purging. */
    public long purgeDrops() {
        return purgeDrops;
    }

    /** Everything the nozzles have thrown. */
    public long totalDrops() {
        return printedDrops + purgeDrops;
    }

    /**
     * Add drops thrown printing.
     *
     * @throws ConfigError when the count is negative
     */
    public void recordPrint(long drops) {
        if (drops < 0) {
            throw new ConfigError("drops", drops, "a print cannot throw negative drops");
        }
        printedDrops += drops;
    }

    /**
     * Add drops thrown purging.
     *
     * @throws ConfigError when the count is negative
     */
    public void recordPurge(long drops) {
        if (drops < 0) {
            throw new ConfigError("drops", drops, "a purge cannot throw negative drops");
        }
        purgeDrops += drops;
    }

    /** Millilitres accounted for at the given drop volume. */
    public double usedMl(double dropVolumePl) {
        return totalDrops() * dropVolumePl / PICOLITRES_PER_ML;
    }

    /** How much of the ink went on purges rather than on product, as a percentage. */
    public double purgeSharePct() {
        if (totalDrops() == 0) {
            return 0.0;
        }
        return (double) purgeDrops / totalDrops() * 100.0;
    }

    @Override
    public String toString() {
        return "InkLedger[printedDrops=" + printedDrops + ", purgeDrops=" + purgeDrops + "]";
    }
}
