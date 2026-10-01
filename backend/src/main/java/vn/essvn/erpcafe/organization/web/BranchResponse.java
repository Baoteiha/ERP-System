package vn.essvn.erpcafe.organization.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Branch representation returned to clients. */
public record BranchResponse(
        UUID id,
        UUID companyId,
        String name,
        String code,
        String address,
        String phone,
        boolean active,
        BigDecimal taxRate,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {
}
