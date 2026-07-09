package vn.essvn.erpcafe.organization.web;

import java.time.Instant;
import java.util.UUID;

/** Company representation returned to clients. */
public record CompanyResponse(
        UUID id,
        String name,
        String code,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {
}
