package vn.essvn.erpcafe.catalog.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CategoryDtos {

    public record CategoryRequest(
            @NotBlank @Size(max = 255) String name,
            int displayOrder,
            Boolean active) {
    }

    public record CategoryResponse(
            UUID id,
            UUID companyId,
            String name,
            int displayOrder,
            boolean active) {
    }

    private CategoryDtos() {
    }
}
