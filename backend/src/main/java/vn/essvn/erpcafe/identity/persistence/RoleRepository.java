package vn.essvn.erpcafe.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.identity.domain.Role;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByCompanyId(UUID companyId);

    Optional<Role> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<Role> findByCompanyIdAndName(UUID companyId, String name);

    boolean existsByCompanyIdAndName(UUID companyId, String name);

    /**
     * Takes the role's row lock until the transaction ends, so a grant of the role and a change to its
     * permissions never interleave. Call before loading the role, so the load reads what the lock protects.
     */
    @Query(value = "SELECT id FROM role WHERE id = :id AND company_id = :companyId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);
}
