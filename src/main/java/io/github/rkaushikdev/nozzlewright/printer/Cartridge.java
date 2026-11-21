package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One cartridge in one pen position, and where its ink went.
 *
 * <p>The machine keeps a drop counter per pen and multiplies it by the drop volume from the firing
 * profile. That estimate drifts, which is why the counter is written back to the cartridge itself
 * rather than kept on the board: move a half used cartridge to another machine and the count moves
 * with it. Shelf life is the other half, counted from the day it was first installed.
 */
public final class Cartridge {

    /** Below this level, as a percentage, the machine warns. It does not stop. */
    public static final double LOW_INK_PCT = 8.0;

    private static final double DEFAULT_DROP_VOLUME_PL = 6.0;

    private final int pen;
    private final CartridgeSpec spec;
    private final String serial;
    private final LocalDate installedOn;
    private final LocalDate manufacturedOn;
    private final InkLedger ledger;
    private final double dropVolumePl;

    /** A cartridge with an empty ledger and the standard six picolitre drop. */
    public Cartridge(int pen, CartridgeSpec spec, String serial, LocalDate installedOn, LocalDate manufacturedOn) {
        this(pen, spec, serial, installedOn, manufacturedOn, new InkLedger(), DEFAULT_DROP_VOLUME_PL);
    }

    /** A cartridge with its own ledger and drop volume. */
    public Cartridge(
            int pen,
            CartridgeSpec spec,
            String serial,
            LocalDate installedOn,
            LocalDate manufacturedOn,
            InkLedger ledger,
            double dropVolumePl) {
        this.pen = pen;
        this.spec = spec;
        this.serial = serial;
        this.installedOn = installedOn;
        this.manufacturedOn = manufacturedOn;
        this.ledger = ledger;
        this.dropVolumePl = dropVolumePl;
    }

    /** The pen position, one to eight. */
    public int pen() {
        return pen;
    }

    /** The part. */
    public CartridgeSpec spec() {
        return spec;
    }

    /** The smartcard serial. */
    public String serial() {
        return serial;
    }

    /** The day it went into a machine. */
    public LocalDate installedOn() {
        return installedOn;
    }

    /** The day it was made. */
    public LocalDate manufacturedOn() {
        return manufacturedOn;
    }

    /** The drops it has thrown, which callers record into. */
    public InkLedger ledger() {
        return ledger;
    }

    /** The drop volume the level is estimated with. */
    public double dropVolumePl() {
        return dropVolumePl;
    }

    /** Ink accounted for so far. */
    public double usedMl() {
        return ledger.usedMl(dropVolumePl);
    }

    /** Ink left, floored at nought. */
    public double remainingMl() {
        return Math.max(spec.nominalMl() - usedMl(), 0.0);
    }

    /** Ink left as a percentage of the nominal fill. */
    public double levelPct() {
        return remainingMl() / spec.nominalMl() * 100.0;
    }

    /** True once the machine should be warning about it. */
    public boolean isLow() {
        return levelPct() <= LOW_INK_PCT;
    }

    /** True once the estimate says there is nothing left. */
    public boolean isEmpty() {
        return remainingMl() <= 0.0;
    }

    /** Last day this cartridge may stay in service. */
    public LocalDate expiresOn() {
        return installedOn.plusDays(spec.family().serviceDays());
    }

    /** True when the cartridge has been in the machine too long. */
    public boolean isExpired(LocalDate today) {
        return today.isAfter(expiresOn());
    }

    /** Days of service life left, negative once it is past. */
    public long daysLeft(LocalDate today) {
        return ChronoUnit.DAYS.between(today, expiresOn());
    }

    /**
     * How many more labels this cartridge can do, roughly: the drops left floor divided by the drops
     * one label throws.
     *
     * @throws ConfigError when a label would throw no drops
     */
    public long printsLeft(long dropsPerLabel) {
        if (dropsPerLabel <= 0) {
            throw new ConfigError("drops_per_label", dropsPerLabel, "a label throws at least one drop");
        }
        double dropsLeft = remainingMl() * InkLedger.PICOLITRES_PER_ML / dropVolumePl;
        return (long) floorDivide(dropsLeft, dropsPerLabel);
    }

    /** Record a full purge and return what it cost in drops. */
    public int purge() {
        int drops = spec.purgeDrops();
        ledger.recordPurge(drops);
        return drops;
    }

    /**
     * Check the cartridge record for anything that cannot be true on {@code today}.
     *
     * @throws ConfigError naming the first thing that is wrong
     */
    public void validate(LocalDate today) {
        spec.validate();
        if (pen < 1 || pen > 8) {
            throw new ConfigError("cartridge.pen", pen, "pens are numbered one to eight");
        }
        if (serial.isBlank()) {
            throw new ConfigError("cartridge.serial", serial, "the smartcard serial is missing");
        }
        if (installedOn.isBefore(manufacturedOn)) {
            throw new ConfigError(
                    "cartridge.installed_on", installedOn, "a cartridge cannot be installed before it was made");
        }
        if (installedOn.isAfter(today)) {
            throw new ConfigError("cartridge.installed_on", installedOn, "the install date is in the future");
        }
        if (dropVolumePl <= 0) {
            throw new ConfigError("cartridge.drop_volume_pl", dropVolumePl, "drop volume must be positive");
        }
    }

    /**
     * The floor of {@code dividend / divisor}, worked from the remainder so a quotient a hair under
     * a whole number is not rounded up into it.
     */
    private static double floorDivide(double dividend, double divisor) {
        double remainder = dividend % divisor;
        double quotient = (dividend - remainder) / divisor;
        if (remainder != 0.0 && (divisor < 0) != (remainder < 0)) {
            quotient -= 1.0;
        }
        double floored = Math.floor(quotient);
        if (quotient - floored > 0.5) {
            floored += 1.0;
        }
        return floored;
    }

    @Override
    public String toString() {
        return "Cartridge[pen=" + pen + ", serial=" + serial + ", spec=" + spec.partNumber() + "]";
    }
}
