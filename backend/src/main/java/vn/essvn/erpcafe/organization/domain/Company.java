package vn.essvn.erpcafe.organization.domain;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * A company: the top of the org hierarchy that owns one or more branches.
 * Soft-deletable master data.
 */
// Soft delete: @SQLDelete turns repository.delete(...) into an UPDATE that flips the flag
// (instead of a real DELETE), and @SQLRestriction transparently adds "deleted = false" to
// every query — so deleted rows survive for audit but disappear from the API.
@Entity
@Table(name = "company", uniqueConstraints = @UniqueConstraint(name = "uk_company_code", columnNames = "code"))
@SQLDelete(sql = "UPDATE company SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Company extends AuditableEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Company() {
        // for JPA
    }

    public Company(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
