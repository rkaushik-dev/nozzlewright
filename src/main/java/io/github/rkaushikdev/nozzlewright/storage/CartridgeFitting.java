package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;

/**
 * One cartridge in one pen position, from fitting to removal.
 *
 * <p>The row is opened when a serial appears in a pen and closed when it goes away. That answers the
 * question a quality department actually asks, which is never how much ink is left and always which
 * cartridge printed this carton. The level is the last one read, not a running total: the machine
 * owns the drop counter and moves it whether or not the gateway was watching.
 */
@Entity
@Table(name = "cartridge_fittings")
public class CartridgeFitting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_pk", nullable = false)
    private Integer printerPk;

    @Column(name = "pen", nullable = false)
    private int pen;

    @Column(name = "serial", length = 32, nullable = false)
    private String serial;

    @Column(name = "part_number", length = 32, nullable = false)
    private String partNumber = "";

    @Column(name = "ink_family", length = 16, nullable = false)
    private String inkFamily = "";

    @Column(name = "fitted_at", nullable = false)
    private Instant fittedAt = Times.now();

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "first_level", nullable = false)
    private int firstLevel = 100;

    @Column(name = "last_level", nullable = false)
    private int lastLevel = 100;

    @Column(name = "readings", nullable = false)
    private int readings = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "printer_pk", insertable = false, updatable = false)
    private Printer printer;

    /** For the persistence provider. */
    protected CartridgeFitting() {}

    /** A new row. */
    public CartridgeFitting(Integer printerPk, int pen, String serial) {
        this.printerPk = printerPk;
        this.pen = pen;
        this.serial = serial;
    }

    /** The row id, once the row has been written. */
    public Integer getId() {
        return id;
    }

    /** The row id of the machine. */
    public Integer getPrinterPk() {
        return printerPk;
    }

    /** Sets it. */
    public void setPrinterPk(Integer value) {
        printerPk = value;
    }

    /** The pen position. */
    public int getPen() {
        return pen;
    }

    /** Sets it. */
    public void setPen(int value) {
        pen = value;
    }

    /** The cartridge serial. */
    public String getSerial() {
        return serial;
    }

    /** Sets it. */
    public void setSerial(String value) {
        serial = value;
    }

    /** The part number. */
    public String getPartNumber() {
        return partNumber;
    }

    /** Sets it. */
    public void setPartNumber(String value) {
        partNumber = value;
    }

    /** The ink family. */
    public String getInkFamily() {
        return inkFamily;
    }

    /** Sets it. */
    public void setInkFamily(String value) {
        inkFamily = value;
    }

    /** When it was first seen in the pen. */
    public Instant getFittedAt() {
        return fittedAt;
    }

    /** Sets it. */
    public void setFittedAt(Instant value) {
        fittedAt = value;
    }

    /** When it left the pen, if it has. */
    public Optional<Instant> getRemovedAt() {
        return Optional.ofNullable(removedAt);
    }

    /** Sets it; {@code null} for none. */
    public void setRemovedAt(Instant value) {
        removedAt = value;
    }

    /** The level when it was fitted, in percent. */
    public int getFirstLevel() {
        return firstLevel;
    }

    /** Sets it. */
    public void setFirstLevel(int value) {
        firstLevel = value;
    }

    /** The last level read, in percent. */
    public int getLastLevel() {
        return lastLevel;
    }

    /** Sets it. */
    public void setLastLevel(int value) {
        lastLevel = value;
    }

    /** How many readings have seen it. */
    public int getReadings() {
        return readings;
    }

    /** Sets it. */
    public void setReadings(int value) {
        readings = value;
    }

    /** The machine this row belongs to. */
    public Printer getPrinter() {
        return printer;
    }

    /** True while the cartridge is still in the machine. */
    public boolean isFitted() {
        return removedAt == null;
    }

    /** How far the level has fallen since it was fitted, never below zero. */
    public int usedPoints() {
        return Math.max(firstLevel - lastLevel, 0);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "<CartridgeFitting pen%d %s %d%%>", pen, serial, lastLevel);
    }
}
