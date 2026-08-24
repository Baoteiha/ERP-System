package vn.essvn.erpcafe.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.identity.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<User> findByCompanyId(UUID companyId, Pageable pageable);

    Optional<User> findByIdAndCompanyId(UUID id, UUID companyId);
}
