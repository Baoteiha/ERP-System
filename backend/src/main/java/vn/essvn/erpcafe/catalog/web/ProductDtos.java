package vn.essvn.erpcafe.catalog.web;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MoneyDto;

public final class ProductDtos {

    public record CreateProductRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @PositiveOrZero BigDecimal basePrice,
            String description) {
    }

    public record UpdateProductRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 64) String sku,
            @NotNull @PositiveOrZero BigDecimal basePrice,
            String description,
            boolean active) {
    }

    public record ProductResponse(
            UUID id,
            UUID companyId,
            UUID categoryId,
            String name,
            String sku,
            String description,
            MoneyDto basePrice,
            boolean active,
            Set<UUID> modifierGroupIds) {
    }

    public record SetModifierGroupsRequest(
            @NotNull Set<UUID> modifierGroupIds) {
    }

    public record BranchAvailabilityRequest(
            @NotNull UUID branchId,
            boolean available,
            BigDecimal priceOverride) {
    }

    public record BranchAvailabilityResponse(
            UUID branchId,
            boolean available,
            MoneyDto priceOverride) {
    }

    public record PriceResponse(
            UUID productId,
            UUID branchId,
            MoneyDto price) {
    }

    private ProductDtos() {
    }
}
