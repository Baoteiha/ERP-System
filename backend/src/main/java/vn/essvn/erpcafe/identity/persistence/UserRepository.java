package vn.essvn.erpcafe.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
