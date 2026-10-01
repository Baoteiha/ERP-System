package vn.essvn.erpcafe.inventory.domain;


import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import vn.essvn.erpcafe.common.domain.TenantOwnedEntity;

/** A supplier the company buys ingredients from. Company-scoped, soft-deletable. */
@Entity
@Table(name = "supplier", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_company_name", columnNames = {"company_id", "name"}))
@SQLDelete(sql = "UPDATE supplier SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Supplier extends TenantOwnedEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "address")
    private String address;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    protected Supplier() {
    }

    public Supplier(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
