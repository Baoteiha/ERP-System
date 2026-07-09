package vn.essvn.erpcafe.organization.domain;

import java.util.UUID;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.AuditableEntity;

/**
 * A branch / outlet belonging to a {@link Company}. Its {@code id} is the
 * {@code branch_id} that scopes branch-scoped data across the rest of the ERP.
 * Soft-deletable master data.
 */
@Entity
@Table(name = "branch", uniqueConstraints = @UniqueConstraint(name = "uk_branch_company_code", columnNames = {"company_id", "code"}))
@SQLDelete(sql = "UPDATE branch SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Branch extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "address")
    private String address;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Branch() {
        // for JPA
    }

    public Branch(UUID companyId, String name, String code) {
        this.companyId = companyId;
        this.name = name;
        this.code = code;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
