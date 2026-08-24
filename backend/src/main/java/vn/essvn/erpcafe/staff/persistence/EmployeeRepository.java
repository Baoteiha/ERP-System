package vn.essvn.erpcafe.staff.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.staff.domain.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findByCompanyIdOrderByFullName(UUID companyId);

    boolean existsByCompanyIdAndFullName(UUID companyId, String fullName);

    Optional<Employee> findByIdAndCompanyId(UUID id, UUID companyId);
}
