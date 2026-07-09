package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByCompanyIdOrderByDisplayOrderAsc(UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
