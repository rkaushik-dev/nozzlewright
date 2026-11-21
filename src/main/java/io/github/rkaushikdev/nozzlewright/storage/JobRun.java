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
 * One print run, from the operator pressing start to whatever ended it.
 *
 * <p>Kept for as long as the products are on the market, not for as long as the poll samples. A
 * customer asking which machine coded a carton in March is asking about this table.
 */
@Entity
@Table(name = "job_runs")
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_pk", nullable = false)
    private Integer printerPk;

    @Column(name = "label", length = 24, nullable = false)
    private String label;

    @Column(name = "label_revision", nullable = false)
    private int labelRevision = 0;

    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome = "";

    @Column(name = "target", nullable = false)
    private int target = 0;

    @Column(name = "printed", nullable = false)
    private int printed = 0;

    @Column(name = "started_counter", nullable = false)
    private int startedCounter = 0;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Times.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "elapsed_s", nullable = false)
    private double elapsedSeconds = 0.0;

    @Column(name = "operator", length = 32, nullable = false)
    private String operator = "";

    @Column(name = "detail", length = 255, nullable = false)
    private String detail = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "printer_pk", insertable = false, updatable = false)
    private Printer printer;

    /** For the persistence provider. */
    protected JobRun() {}

    /** A new row. */
    public JobRun(Integer printerPk, String label) {
        this.printerPk = printerPk;
        this.label = label;
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

    /** The label printed. */
    public String getLabel() {
        return label;
    }

    /** Sets it. */
    public void setLabel(String value) {
        label = value;
    }

    /** Its revision. */
    public int getLabelRevision() {
        return labelRevision;
    }

    /** Sets it. */
    public void setLabelRevision(int value) {
        labelRevision = value;
    }

    /** How the run ended. */
    public String getOutcome() {
        return outcome;
    }

    /** Sets it. */
    public void setOutcome(String value) {
        outcome = value;
    }

    /** How many codes were asked for; zero for no target. */
    public int getTarget() {
        return target;
    }

    /** Sets it. */
    public void setTarget(int value) {
        target = value;
    }

    /** How many were printed. */
    public int getPrinted() {
        return printed;
    }

    /** Sets it. */
    public void setPrinted(int value) {
        printed = value;
    }

    /** The machine print counter at the start. */
    public int getStartedCounter() {
        return startedCounter;
    }

    /** Sets it. */
    public void setStartedCounter(int value) {
        startedCounter = value;
    }

    /** When it started. */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** Sets it. */
    public void setStartedAt(Instant value) {
        startedAt = value;
    }

    /** When it ended, if it has. */
    public Optional<Instant> getFinishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    /** Sets it; {@code null} for none. */
    public void setFinishedAt(Instant value) {
        finishedAt = value;
    }

    /** How long it ran, in seconds. */
    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    /** Sets it. */
    public void setElapsedSeconds(double value) {
        elapsedSeconds = value;
    }

    /** Who ran it. */
    public String getOperator() {
        return operator;
    }

    /** Sets it. */
    public void setOperator(String value) {
        operator = value;
    }

    /** Anything worth saying about how it ended. */
    public String getDetail() {
        return detail;
    }

    /** Sets it. */
    public void setDetail(String value) {
        detail = value;
    }

    /** The machine this row belongs to. */
    public Printer getPrinter() {
        return printer;
    }

    /** True when the run ended before it printed everything. */
    public boolean isShort() {
        return target > 0 && printed < target;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "<JobRun %s %s %d/%d>", label, outcome, printed, target);
    }
}
