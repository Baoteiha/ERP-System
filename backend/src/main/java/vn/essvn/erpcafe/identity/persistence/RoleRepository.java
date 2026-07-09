package vn.essvn.erpcafe.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.identity.domain.Role;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByCompanyId(UUID companyId);

    Optional<Role> findByCompanyIdAndName(UUID companyId, String name);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
