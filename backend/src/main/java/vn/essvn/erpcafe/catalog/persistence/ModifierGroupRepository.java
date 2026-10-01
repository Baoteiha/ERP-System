package vn.essvn.erpcafe.catalog.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.ModifierGroup;

/** Tenant-owned: every query here sees only the current company's groups (see {@code TenantOwnedEntity}). */
public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, UUID> {

    boolean existsByName(String name);
}
