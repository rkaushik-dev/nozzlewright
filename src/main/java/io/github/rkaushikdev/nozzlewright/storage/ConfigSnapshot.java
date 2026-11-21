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

/** A machine configuration as it was at one moment. */
@Entity
@Table(name = "config_snapshots")
public class ConfigSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_pk", nullable = false)
    private Integer printerPk;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt = Times.now();

    @Column(name = "digest", length = 32, nullable = false)
    private String digest;

    @Column(name = "payload", length = 8192, nullable = false)
    private String payload;

    @Column(name = "reason", length = 64, nullable = false)
    private String reason = "";

    @Column(name = "taken_by", length = 32, nullable = false)
    private String takenBy = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "printer_pk", insertable = false, updatable = false)
    private Printer printer;

    /** For the persistence provider. */
    protected ConfigSnapshot() {}

    /** A new row. */
    public ConfigSnapshot(Integer printerPk, String digest, String payload) {
        this.printerPk = printerPk;
        this.digest = digest;
        this.payload = payload;
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

    /** When it was read. */
    public Instant getTakenAt() {
        return takenAt;
    }

    /** Sets it. */
    public void setTakenAt(Instant value) {
        takenAt = value;
    }

    /** The digest of the payload. */
    public String getDigest() {
        return digest;
    }

    /** Sets it. */
    public void setDigest(String value) {
        digest = value;
    }

    /** The configuration as the machine wrote it. */
    public String getPayload() {
        return payload;
    }

    /** Sets it. */
    public void setPayload(String value) {
        payload = value;
    }

    /** Why it was taken. */
    public String getReason() {
        return reason;
    }

    /** Sets it. */
    public void setReason(String value) {
        reason = value;
    }

    /** Who took it. */
    public String getTakenBy() {
        return takenBy;
    }

    /** Sets it. */
    public void setTakenBy(String value) {
        takenBy = value;
    }

    /** The machine this row belongs to. */
    public Printer getPrinter() {
        return printer;
    }

    @Override
    public String toString() {
        return "<ConfigSnapshot " + printerPk + " " + digest + ">";
    }
}
