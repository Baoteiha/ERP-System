package vn.essvn.erpcafe.organization.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create/update payload for a branch. */
public record BranchRequest(
        @NotNull UUID companyId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code,
        @Size(max = 500) String address,
        @Size(max = 32) String phone,
        Boolean active) {
}
