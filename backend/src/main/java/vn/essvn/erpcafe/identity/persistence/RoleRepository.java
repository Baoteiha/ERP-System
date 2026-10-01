package vn.essvn.erpcafe.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.identity.domain.Role;

/** Roles are tenant-owned: every JPQL query here sees only the current company's (see {@code TenantOwnedEntity}). */
@Transactional(readOnly = true)
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    /**
     * Takes the role's row lock until the transaction ends, so a grant of the role and a change to its
     * permissions never interleave. Call before loading the role, so the load reads what the lock protects.
     * Native SQL passes the tenant filter by, so it names the company itself.
     */
    @Transactional
    @Query(value = "SELECT id FROM role WHERE id = :id AND company_id = :companyId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);
}
