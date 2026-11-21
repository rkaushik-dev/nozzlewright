package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One thing somebody did to a machine.
 *
 * <p>Separate from the poll samples on purpose: a sample is what the machine was doing, this is what
 * a person told it to do. An auditor wants to know who changed the firing voltage on the fourteenth.
 * Rows are never updated and never pruned: an audit trail with a retention policy is not an audit
 * trail. There is no foreign key to the machine, so taking a machine off the site keeps its record.
 */
@Entity
@Table(name = "audit_entries")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "at", nullable = false)
    private Instant at = Times.now();

    @Column(name = "actor", length = 32, nullable = false)
    private String actor = "";

    @Column(name = "action", length = 32, nullable = false)
    private String action;

    @Column(name = "printer_id", length = 32, nullable = false)
    private String printerId = "";

    @Column(name = "subject", length = 64, nullable = false)
    private String subject = "";

    @Column(name = "before", length = 255, nullable = false)
    private String before = "";

    @Column(name = "after", length = 255, nullable = false)
    private String after = "";

    @Column(name = "detail", length = 255, nullable = false)
    private String detail = "";

    /** For the persistence provider. */
    protected AuditEntry() {}

    /** A new row. */
    public AuditEntry(String action) {
        this.action = action;
    }

    /** The row id, once the row has been written. */
    public Integer getId() {
        return id;
    }

    /** When it happened. */
    public Instant getAt() {
        return at;
    }

    /** Sets it. */
    public void setAt(Instant value) {
        at = value;
    }

    /** Who did it. */
    public String getActor() {
        return actor;
    }

    /** Sets it. */
    public void setActor(String value) {
        actor = value;
    }

    /** What was done. */
    public String getAction() {
        return action;
    }

    /** Sets it. */
    public void setAction(String value) {
        action = value;
    }

    /** The machine it was done to. */
    public String getPrinterId() {
        return printerId;
    }

    /** Sets it. */
    public void setPrinterId(String value) {
        printerId = value;
    }

    /** What on the machine it touched. */
    public String getSubject() {
        return subject;
    }

    /** Sets it. */
    public void setSubject(String value) {
        subject = value;
    }

    /** The value before. */
    public String getBefore() {
        return before;
    }

    /** Sets it. */
    public void setBefore(String value) {
        before = value;
    }

    /** The value after. */
    public String getAfter() {
        return after;
    }

    /** Sets it. */
    public void setAfter(String value) {
        after = value;
    }

    /** Anything else worth keeping. */
    public String getDetail() {
        return detail;
    }

    /** Sets it. */
    public void setDetail(String value) {
        detail = value;
    }

    @Override
    public String toString() {
        return "<AuditEntry " + (actor.isEmpty() ? "?" : actor) + " " + action + " " + subject + ">";
    }
}
