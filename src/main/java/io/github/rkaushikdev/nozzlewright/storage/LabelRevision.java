package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One version of one label, as it was pushed to a machine. Unique per name and revision. */
@Entity
@Table(name = "label_revisions")
public class LabelRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "name", length = 24, nullable = false)
    private String name;

    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "digest", length = 32, nullable = false)
    private String digest;

    @Column(name = "source", length = 8192, nullable = false)
    private String source;

    @Column(name = "height_dots", nullable = false)
    private int heightDots = 0;

    @Column(name = "interactive", nullable = false)
    private boolean interactive = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Column(name = "created_by", length = 32, nullable = false)
    private String createdBy = "";

    /** For the persistence provider. */
    protected LabelRevision() {}

    /** A new row. */
    public LabelRevision(String name, int revision, String digest, String source) {
        this.name = name;
        this.revision = revision;
        this.digest = digest;
        this.source = source;
    }

    /** The row id, once the row has been written. */
    public Integer getId() {
        return id;
    }

    /** The label name. */
    public String getName() {
        return name;
    }

    /** Sets it. */
    public void setName(String value) {
        name = value;
    }

    /** The revision number, from one. */
    public int getRevision() {
        return revision;
    }

    /** Sets it. */
    public void setRevision(int value) {
        revision = value;
    }

    /** The content digest. */
    public String getDigest() {
        return digest;
    }

    /** Sets it. */
    public void setDigest(String value) {
        digest = value;
    }

    /** The label source text. */
    public String getSource() {
        return source;
    }

    /** Sets it. */
    public void setSource(String value) {
        source = value;
    }

    /** The label height in dots. */
    public int getHeightDots() {
        return heightDots;
    }

    /** Sets it. */
    public void setHeightDots(int value) {
        heightDots = value;
    }

    /** Whether it asks the operator for a value. */
    public boolean isInteractive() {
        return interactive;
    }

    /** Sets it. */
    public void setInteractive(boolean value) {
        interactive = value;
    }

    /** When the revision was stored. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets it. */
    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    /** Who stored it. */
    public String getCreatedBy() {
        return createdBy;
    }

    /** Sets it. */
    public void setCreatedBy(String value) {
        createdBy = value;
    }

    @Override
    public String toString() {
        return "<LabelRevision " + name + " r" + revision + ">";
    }
}
