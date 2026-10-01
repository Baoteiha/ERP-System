package vn.essvn.erpcafe.organization.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a branch. The owning company is never client-supplied:
 * it is taken from the authenticated principal, so a caller cannot create a branch
 * in, or move a branch to, another tenant.
 */
public record BranchRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code,
        @Size(max = 500) String address,
        @Size(max = 32) String phone,
        Boolean active,
        @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate) {
}
