package vn.essvn.erpcafe.inventory.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.Supplier;

/** Suppliers are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    boolean existsByName(String name);
}
