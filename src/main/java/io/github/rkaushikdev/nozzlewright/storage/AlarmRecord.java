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

/** One alarm, from the poll that raised it to the poll that cleared it. */
@Entity
@Table(name = "alarm_records")
public class AlarmRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_pk", nullable = false)
    private Integer printerPk;

    @Column(name = "name", length = 32, nullable = false)
    private String name;

    @Column(name = "severity", length = 16, nullable = false)
    private String severity = "warning";

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt = Times.now();

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "seen", nullable = false)
    private int seen = 1;

    @Column(name = "acknowledged_by", length = 32, nullable = false)
    private String acknowledgedBy = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "printer_pk", insertable = false, updatable = false)
    private Printer printer;

    /** For the persistence provider. */
    protected AlarmRecord() {}

    /** A new row. */
    public AlarmRecord(Integer printerPk, String name) {
        this.printerPk = printerPk;
        this.name = name;
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

    /** The alarm name. */
    public String getName() {
        return name;
    }

    /** Sets it. */
    public void setName(String value) {
        name = value;
    }

    /** Its severity word. */
    public String getSeverity() {
        return severity;
    }

    /** Sets it. */
    public void setSeverity(String value) {
        severity = value;
    }

    /** When it was first seen. */
    public Instant getRaisedAt() {
        return raisedAt;
    }

    /** Sets it. */
    public void setRaisedAt(Instant value) {
        raisedAt = value;
    }

    /** When it cleared, if it has. */
    public Optional<Instant> getClearedAt() {
        return Optional.ofNullable(clearedAt);
    }

    /** Sets it; {@code null} for none. */
    public void setClearedAt(Instant value) {
        clearedAt = value;
    }

    /** How many polls have reported it. */
    public int getSeen() {
        return seen;
    }

    /** Sets it. */
    public void setSeen(int value) {
        seen = value;
    }

    /** Who acknowledged it, empty when nobody has. */
    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    /** Sets it. */
    public void setAcknowledgedBy(String value) {
        acknowledgedBy = value;
    }

    /** The machine this row belongs to. */
    public Printer getPrinter() {
        return printer;
    }

    /** True while the machine is still reporting it. */
    public boolean isOpen() {
        return clearedAt == null;
    }

    @Override
    public String toString() {
        return "<AlarmRecord " + name + " " + (isOpen() ? "open" : "closed") + ">";
    }
}
