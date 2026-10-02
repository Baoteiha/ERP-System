package vn.essvn.erpcafe.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Class: RecipeVersion
 * Purpose: One saved, never-changed revision of a product's recipe, which a line taken under it consumes when sold.
 */
@Entity
@Immutable
@Table(name = "recipe_version")
@EntityListeners(AuditingEntityListener.class)
public class RecipeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false, updatable = false)
    private Recipe recipe;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // not null: the key goes into the line's own INSERT, which row-level security admits only under a visible version
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recipe_line", joinColumns = @JoinColumn(name = "recipe_version_id", nullable = false))
    private List<ConsumptionLine> lines = new ArrayList<>();

    protected RecipeVersion() {
    }

    RecipeVersion(Recipe recipe, int versionNo, List<ConsumptionLine> lines) {
        this.recipe = recipe;
        this.versionNo = versionNo;
        this.lines = new ArrayList<>(lines);
    }

    public UUID getId() {
        return id;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public List<ConsumptionLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
