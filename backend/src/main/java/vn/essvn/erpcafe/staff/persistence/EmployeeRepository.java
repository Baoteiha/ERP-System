package vn.essvn.erpcafe.staff.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.staff.domain.Employee;

/** Employees are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findAllByOrderByFullName();
}
