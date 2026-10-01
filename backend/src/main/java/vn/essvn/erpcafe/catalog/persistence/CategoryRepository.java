package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Category;

/** Categories are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderByDisplayOrderAsc();

    boolean existsByName(String name);
}
