package vn.essvn.erpcafe.identity.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.identity.domain.User;

/** Users are tenant-owned: every JPQL query here sees only the current company's (see {@code TenantOwnedEntity}). */
@Transactional(readOnly = true)
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Login reads this as the system, before any company is known. */
    Optional<User> findByEmail(String email);

    /**
     * Emails are unique across every company, since login finds the user by email alone; native SQL
     * passes the tenant filter by, and run as the system (owner connection) row-level security does
     * too, so another company's holder of the email is seen.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM app_user WHERE email = :email AND deleted = false)",
            nativeQuery = true)
    boolean existsByEmailInAnyCompany(@Param("email") String email);

    /**
     * The version every access token issued to the user now carries; empty once the user is gone. Not
     * mapped on {@link User}, so no write of the entity can put back a version it read before a bump.
     */
    @Query(value = "SELECT auth_version FROM app_user WHERE id = :id AND company_id = :companyId AND deleted = false",
            nativeQuery = true)
    Optional<Integer> findAuthVersion(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** Moves the users' auth version on, in the caller's transaction, so every token they hold now is refused. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "UPDATE app_user SET auth_version = auth_version + 1 WHERE id IN (:ids) AND company_id = :companyId",
            nativeQuery = true)
    int bumpAuthVersion(@Param("ids") Collection<UUID> ids, @Param("companyId") UUID companyId);
}
