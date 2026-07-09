package vn.essvn.erpcafe.catalog.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Modifier;

public interface ModifierRepository extends JpaRepository<Modifier, UUID> {
}
