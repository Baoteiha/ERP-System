package vn.essvn.erpcafe.inventory.mapper;

import org.mapstruct.Mapper;

import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.web.IngredientDtos.IngredientResponse;

@Mapper(componentModel = "spring")
public interface IngredientMapper {

    IngredientResponse toResponse(Ingredient ingredient);
}
