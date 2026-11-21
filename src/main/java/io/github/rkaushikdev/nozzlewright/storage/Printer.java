package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One machine on the site, as the gateway keeps it.
 *
 * <p>Its samples, alarms, snapshots, runs and cartridge fittings hang off it and go with it when it
 * is deleted; the database cascades the delete, so a machine with a year of samples is removed
 * without loading them.
 */
@Entity
@Table(name = "printers")
public class Printer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "printer_id", length = 32, nullable = false)
    private String printerId;

    @Column(name = "endpoint", length = 128, nullable = false)
    private String endpoint;

    @Column(name = "kind", length = 16, nullable = false)
    private String kind = "tcp";

    @Column(name = "line", length = 32, nullable = false)
    private String line = "";

    @Column(name = "description", length = 255, nullable = false)
    private String description = "";

    @Column(name = "family", length = 16, nullable = false)
    private String family = "";

    @Column(name = "firmware", length = 32, nullable = false)
    private String firmware = "";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Column(name = "seen_at")
    private Instant seenAt;

    @OneToMany(mappedBy = "printer")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("id")
    private List<PollSample> samples = new ArrayList<>();

    @OneToMany(mappedBy = "printer")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("id")
    private List<AlarmRecord> alarms = new ArrayList<>();

    @OneToMany(mappedBy = "printer")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("id")
    private List<ConfigSnapshot> snapshots = new ArrayList<>();

    @OneToMany(mappedBy = "printer")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("id")
    private List<JobRun> jobs = new ArrayList<>();

    @OneToMany(mappedBy = "printer")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("id")
    private List<CartridgeFitting> cartridges = new ArrayList<>();

    /** For the persistence provider. */
    protected Printer() {}

    /** A new machine at {@code endpoint}. */
    public Printer(String printerId, String endpoint) {
        this.printerId = printerId;
        this.endpoint = endpoint;
    }

    /** The row id, once the row has been written. */
    public Integer getId() {
        return id;
    }

    /** The registry id of the machine. */
    public String getPrinterId() {
        return printerId;
    }

    /** Sets the registry id. */
    public void setPrinterId(String value) {
        printerId = value;
    }

    /** Where the machine is reached. */
    public String getEndpoint() {
        return endpoint;
    }

    /** Sets the endpoint. */
    public void setEndpoint(String value) {
        endpoint = value;
    }

    /** The transport kind: tcp, rfcomm or loopback. */
    public String getKind() {
        return kind;
    }

    /** Sets the transport kind. */
    public void setKind(String value) {
        kind = value;
    }

    /** The production line it sits on. */
    public String getLine() {
        return line;
    }

    /** Sets the line. */
    public void setLine(String value) {
        line = value;
    }

    /** A free text description. */
    public String getDescription() {
        return description;
    }

    /** Sets the description. */
    public void setDescription(String value) {
        description = value;
    }

    /** The family the machine reported, empty until it has. */
    public String getFamily() {
        return family;
    }

    /** Sets the family. */
    public void setFamily(String value) {
        family = value;
    }

    /** The firmware build the machine reported, empty until it has. */
    public String getFirmware() {
        return firmware;
    }

    /** Sets the firmware build. */
    public void setFirmware(String value) {
        firmware = value;
    }

    /** Whether the gateway polls it. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether it is polled. */
    public void setEnabled(boolean value) {
        enabled = value;
    }

    /** When the row was made. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets when the row was made. */
    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    /** When the machine last answered a poll. */
    public Optional<Instant> getSeenAt() {
        return Optional.ofNullable(seenAt);
    }

    /** Sets when the machine last answered, {@code null} for never. */
    public void setSeenAt(Instant value) {
        seenAt = value;
    }

    /** Its poll samples, oldest row first. */
    public List<PollSample> getSamples() {
        return List.copyOf(samples);
    }

    /** Its alarms, oldest row first. */
    public List<AlarmRecord> getAlarms() {
        return List.copyOf(alarms);
    }

    /** Its configuration snapshots, oldest row first. */
    public List<ConfigSnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /** Its print runs, oldest row first. */
    public List<JobRun> getJobs() {
        return List.copyOf(jobs);
    }

    /** Its cartridge fittings, oldest row first. */
    public List<CartridgeFitting> getCartridges() {
        return List.copyOf(cartridges);
    }

    @Override
    public String toString() {
        return "<Printer " + printerId + " at " + endpoint + ">";
    }
}
