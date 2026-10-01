package vn.essvn.erpcafe.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RecipeValidationIntegrationTest
 * Purpose: Proves recipes and modifier deltas are rejected at save when an ingredient or unit cannot be consumed.
 */
class RecipeValidationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private IngredientRepository ingredientRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private RecipeRepository recipeRepository;

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) throws Exception {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())).contentType(MediaType.APPLICATION_JSON);
    }

    private String created(String url, String body) throws Exception {
        String json = mockMvc.perform(authed(post(url)).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String ingredient(String baseUnit) throws Exception {
        return created("/api/v1/ingredients",
                "{\"name\":\"RecipeVal%d\",\"baseUnit\":\"%s\"}".formatted(SEQ.incrementAndGet(), baseUnit));
    }

    private String product() throws Exception {
        int n = SEQ.incrementAndGet();
        String categoryId = created("/api/v1/categories", "{\"name\":\"RV%d\",\"displayOrder\":1}".formatted(n));
        return created("/api/v1/products",
                "{\"categoryId\":\"%s\",\"name\":\"RV%d\",\"sku\":\"RV%d\",\"basePrice\":1000}"
                        .formatted(categoryId, n, n));
    }

    private ResultActions setRecipe(String productId, String ingredientId, String qty, String unit) throws Exception {
        return mockMvc.perform(authed(put("/api/v1/products/" + productId + "/recipe"))
                .content("{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}]}"
                        .formatted(ingredientId, qty, unit)));
    }

    private void expectRefused(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void recipe_withAnUnknownIngredientIsRefusedAndNothingIsSaved() throws Exception {
        String productId = product();

        expectRefused(setRecipe(productId, UUID.randomUUID().toString(), "5", "g"));

        assertThat(recipeRepository.findByProductId(UUID.fromString(productId))).isEmpty();
    }

    @Test
    void recipe_withAnotherCompanysIngredientIsRefused() throws Exception {
        int n = SEQ.incrementAndGet();
        Company other = companyRepository.save(new Company("RecipeVal Foreign " + n, "RVF" + n));
        Ingredient foreign = TenantContext.runAs(other.getId(), () -> ingredientRepository.save(
                new Ingredient("ForeignBeans" + SEQ.incrementAndGet(), "g", null)));

        expectRefused(setRecipe(product(), foreign.getId().toString(), "5", "g"));
    }

    @Test
    void recipe_withAnUnknownUnitIsRefused() throws Exception {
        expectRefused(setRecipe(product(), ingredient("g"), "5", "cup"));
    }

    @Test
    void recipe_inAnotherDimensionThanTheIngredientIsRefused() throws Exception {
        expectRefused(setRecipe(product(), ingredient("ml"), "5", "g"));
    }

    @Test
    void recipe_withAValidLineInAConvertibleUnitIsSaved() throws Exception {
        setRecipe(product(), ingredient("g"), "0.5", "kg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    void modifierDelta_withAnUnknownIngredientOrWrongDimensionIsRefusedAndNothingIsSaved() throws Exception {
        String groupId = created("/api/v1/modifier-groups",
                "{\"name\":\"RVGrp%d\",\"minSelect\":0,\"maxSelect\":1}".formatted(SEQ.incrementAndGet()));
        String url = "/api/v1/modifier-groups/" + groupId + "/modifiers";

        expectRefused(mockMvc.perform(authed(post(url)).content(
                "{\"name\":\"X\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":-7,\"unit\":\"g\"}]}"
                        .formatted(UUID.randomUUID()))));
        expectRefused(mockMvc.perform(authed(post(url)).content(
                "{\"name\":\"Y\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":7,\"unit\":\"g\"}]}"
                        .formatted(ingredient("ml")))));

        mockMvc.perform(authed(get("/api/v1/modifier-groups/" + groupId)))
                .andExpect(jsonPath("$.modifiers.length()").value(0));
    }

    @Test
    void modifierDelta_withAValidSignedLineIsSaved() throws Exception {
        String groupId = created("/api/v1/modifier-groups",
                "{\"name\":\"RVOk%d\",\"minSelect\":0,\"maxSelect\":1}".formatted(SEQ.incrementAndGet()));

        mockMvc.perform(authed(post("/api/v1/modifier-groups/" + groupId + "/modifiers")).content(
                "{\"name\":\"Less\",\"priceDelta\":0,\"displayOrder\":1,\"recipeDeltas\":[{\"ingredientId\":\"%s\",\"quantity\":-200,\"unit\":\"ml\"}]}"
                        .formatted(ingredient("l"))))
                .andExpect(status().is2xxSuccessful());
    }
}
