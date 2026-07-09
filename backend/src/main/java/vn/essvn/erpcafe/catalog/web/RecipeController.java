package vn.essvn.erpcafe.catalog.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.catalog.application.ConsumptionLine;
import vn.essvn.erpcafe.catalog.application.RecipeService;
import vn.essvn.erpcafe.catalog.domain.Recipe;
import vn.essvn.erpcafe.catalog.web.RecipeDtos.RecipeResponse;
import vn.essvn.erpcafe.catalog.web.RecipeDtos.SetRecipeRequest;
import vn.essvn.erpcafe.common.web.ApiVersions;

@RestController
@RequestMapping(ApiVersions.V1 + "/products/{productId}/recipe")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public RecipeResponse get(@PathVariable UUID productId) {
        return toResponse(recipeService.getForProduct(productId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('catalog:write')")
    public RecipeResponse set(@PathVariable UUID productId, @Valid @RequestBody SetRecipeRequest request) {
        List<ConsumptionLine> lines = request.lines().stream()
                .map(l -> new ConsumptionLine(l.ingredientId(), l.quantity(), l.unit()))
                .toList();
        return toResponse(recipeService.setRecipe(productId, lines));
    }

    private RecipeResponse toResponse(Recipe recipe) {
        List<ConsumptionLineDto> lines = recipe.getLines().stream()
                .map(l -> new ConsumptionLineDto(l.getIngredientId(), l.getQuantity(), l.getUnit()))
                .toList();
        return new RecipeResponse(recipe.getProductId(), lines);
    }
}
