package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.ModifierGroup;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, UUID> {

    List<ModifierGroup> findByCompanyId(UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
