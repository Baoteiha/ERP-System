package vn.essvn.erpcafe.catalog.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vn.essvn.erpcafe.common.web.MoneyDto;

public final class ModifierDtos {

    public record CreateModifierGroupRequest(
            @NotBlank String name,
            @PositiveOrZero int minSelect,
            @PositiveOrZero int maxSelect) {

        @JsonIgnore
        @AssertTrue(message = "minSelect must not exceed maxSelect")
        public boolean isSelectRangeValid() {
            return minSelect <= maxSelect;
        }
    }

    public record AddModifierRequest(
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal priceDelta,
            int displayOrder,
            @Valid List<ConsumptionLineDto> recipeDeltas) {
    }

    public record ModifierResponse(
            UUID id,
            String name,
            MoneyDto priceDelta,
            int displayOrder,
            boolean active) {
    }

    public record ModifierGroupResponse(
            UUID id,
            UUID companyId,
            String name,
            int minSelect,
            int maxSelect,
            boolean required,
            List<ModifierResponse> modifiers) {
    }

    private ModifierDtos() {
    }
}
