package vn.essvn.erpcafe.staff.domain;

import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.AuditableEntity;
import vn.essvn.erpcafe.common.domain.Money;

/**
 * A staff member of a company. Master data: employees are deactivated, never
 * deleted, because completed shifts reference them for labor history. The
 * hourly rate is the employee's current rate; each shift snapshots the rate
 * at clock-in so past labor costs never drift when rates change.
 */
@Entity
@Table(name = "employee")
public class Employee extends AuditableEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "position", length = 100)
    private String position;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email")
    private String email;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "hourly_rate", nullable = false, precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "hourly_rate_currency", nullable = false, length = 3)),
    })
    private Money hourlyRate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Employee() {
    }

    public Employee(UUID companyId, String fullName, String position, Money hourlyRate) {
        this.companyId = companyId;
        this.fullName = fullName;
        this.position = position;
        this.hourlyRate = hourlyRate == null ? Money.zero() : hourlyRate;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Money getHourlyRate() {
        return hourlyRate;
    }

    public void setHourlyRate(Money hourlyRate) {
        this.hourlyRate = hourlyRate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
