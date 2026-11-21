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
import java.util.OptionalDouble;

/**
 * One reading of one machine.
 *
 * <p>The only table that grows quickly: at one row per machine per fifteen seconds a forty machine
 * site writes about two hundred thousand rows a day, which is fine as long as the retention job runs.
 * Counters are stored as they were read, not as deltas, because a delta cannot be recomputed once
 * written and a reboot mid shift makes it wrong.
 */
@Entity
@Table(name = "poll_samples")
public class PollSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_pk", nullable = false)
    private Integer printerPk;

    @Column(name = "at", nullable = false)
    private Instant at = Times.now();

    @Column(name = "reachable", nullable = false)
    private boolean reachable = true;

    @Column(name = "state", length = 16, nullable = false)
    private String state = "";

    @Column(name = "alarms", nullable = false)
    private int alarms = 0;

    @Column(name = "printed", nullable = false)
    private int printed = 0;

    @Column(name = "head_temperature_c")
    private Double headTemperatureC;

    @Column(name = "line_speed_mm_per_min")
    private Double lineSpeedMmPerMin;

    @Column(name = "duration_s", nullable = false)
    private double durationSeconds = 0.0;

    @Column(name = "error", length = 255, nullable = false)
    private String error = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "printer_pk", insertable = false, updatable = false)
    private Printer printer;

    /** For the persistence provider. */
    protected PollSample() {}

    /** A new row. */
    public PollSample(Integer printerPk, Instant at) {
        this.printerPk = printerPk;
        this.at = at;
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

    /** When the poll ran. */
    public Instant getAt() {
        return at;
    }

    /** Sets it. */
    public void setAt(Instant value) {
        at = value;
    }

    /** Whether the machine answered. */
    public boolean isReachable() {
        return reachable;
    }

    /** Sets it. */
    public void setReachable(boolean value) {
        reachable = value;
    }

    /** The machine state it reported. */
    public String getState() {
        return state;
    }

    /** Sets it. */
    public void setState(String value) {
        state = value;
    }

    /** The alarm word it reported. */
    public int getAlarms() {
        return alarms;
    }

    /** Sets it. */
    public void setAlarms(int value) {
        alarms = value;
    }

    /** The print counter it reported. */
    public int getPrinted() {
        return printed;
    }

    /** Sets it. */
    public void setPrinted(int value) {
        printed = value;
    }

    /** The head temperature, when the machine reported one. */
    public OptionalDouble getHeadTemperatureC() {
        return headTemperatureC == null ? OptionalDouble.empty() : OptionalDouble.of(headTemperatureC);
    }

    /** Sets it; {@code null} for none. */
    public void setHeadTemperatureC(Double value) {
        headTemperatureC = value;
    }

    /** The line speed, when the machine reported one. */
    public OptionalDouble getLineSpeedMmPerMin() {
        return lineSpeedMmPerMin == null ? OptionalDouble.empty() : OptionalDouble.of(lineSpeedMmPerMin);
    }

    /** Sets it; {@code null} for none. */
    public void setLineSpeedMmPerMin(Double value) {
        lineSpeedMmPerMin = value;
    }

    /** How long the poll took, in seconds. */
    public double getDurationSeconds() {
        return durationSeconds;
    }

    /** Sets it. */
    public void setDurationSeconds(double value) {
        durationSeconds = value;
    }

    /** Why the poll failed, empty when it did not. */
    public String getError() {
        return error;
    }

    /** Sets it. */
    public void setError(String value) {
        error = value;
    }

    /** The machine this row belongs to. */
    public Printer getPrinter() {
        return printer;
    }

    @Override
    public String toString() {
        return "<PollSample " + printerPk + " " + Times.text(at) + ">";
    }
}
