package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Item master data: the bridge between what a supplier sells and what stock counts.
 * The rule under test is the unit one — an item may be bought in any unit measuring
 * the same thing as its ingredient's base unit, and in no other.
 */
class ItemFlowIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) throws Exception {
        if (token == null) {
            token = adminToken();
        }
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token));
    }

    private String create(String url, String body) throws Exception {
        return mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    // Ingredient and supplier names are unique per company and the whole suite shares one
    // database, so these are prefixed to stay clear of the other inventory tests' fixtures.
    private String ingredient(String baseUnit) throws Exception {
        return JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"ItemBeans%d\",\"baseUnit\":\"%s\"}".formatted(SEQ.incrementAndGet(), baseUnit)), "$.id");
    }

    private String supplier() throws Exception {
        return JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"ItemRoaster%d\"}".formatted(SEQ.incrementAndGet())), "$.id");
    }

    private static String itemBody(String supplierId, String ingredientId, String unit, String sku) {
        return "{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"%s\"%s}"
                .formatted(supplierId, ingredientId, unit, sku == null ? "" : ",\"sku\":\"" + sku + "\"");
    }

    @Test
    void create_mapsAPurchaseUnitOntoAnIngredientCountedInAnother() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();

        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplierId, ingredientId, "kg", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("kg"))
                .andExpect(jsonPath("$.ingredientId").value(ingredientId))
                .andExpect(jsonPath("$.supplierId").value(supplierId))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_canonicalisesTheUnitSpelling() throws Exception {
        String json = create("/api/v1/items", itemBody(supplier(), ingredient("ml"), "Litre", null));
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(json, "$.unit")).isEqualTo("l");
    }

    @Test
    void create_rejectsAUnitFromAnotherDimension() throws Exception {
        // Beans are counted in grams; buying them "by the litre" is a mapping error, and
        // now is the only moment it can still be fixed cheaply.
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplier(), ingredient("g"), "l", null)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void create_rejectsAnUnknownUnit() throws Exception {
        // "sack" is packaging, not a unit — it has no factor without knowing what is in it.
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplier(), ingredient("g"), "sack", null)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void create_allowsTheSameIngredientInDifferentUnitsButNotTwiceInOne() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        create("/api/v1/items", itemBody(supplierId, ingredientId, "kg", null));

        // A 1 kg bag and a 1 g sample are different ways to buy the same thing.
        create("/api/v1/items", itemBody(supplierId, ingredientId, "g", null));

        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplierId, ingredientId, "kg", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void create_rejectsADuplicateSkuForTheSameSupplier() throws Exception {
        String supplierId = supplier();
        String sku = "SKU-" + SEQ.incrementAndGet();
        create("/api/v1/items", itemBody(supplierId, ingredient("g"), "kg", sku));

        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplierId, ingredient("g"), "kg", sku)))
                .andExpect(status().isConflict());
    }

    @Test
    void create_rejectsAnIngredientFromAnotherCompany() throws Exception {
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplier(), java.util.UUID.randomUUID().toString(), "kg", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_filtersBySupplierAndByIngredient() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        create("/api/v1/items", itemBody(supplierId, ingredientId, "kg", null));

        mockMvc.perform(authed(get("/api/v1/items?supplierId=" + supplierId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ingredientId").value(ingredientId));

        mockMvc.perform(authed(get("/api/v1/items?ingredientId=" + ingredientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].supplierId").value(supplierId));
    }

    @Test
    void update_changesTheUnitButKeepsTheMapping() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String json = create("/api/v1/items", itemBody(supplierId, ingredientId, "kg", null));
        String id = JsonPath.read(json, "$.id");
        int version = JsonPath.read(json, "$.version");

        mockMvc.perform(authed(put("/api/v1/items/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                                + "\"unit\":\"g\",\"version\":%d}").formatted(supplierId, ingredientId, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("g"))
                .andExpect(jsonPath("$.ingredientId").value(ingredientId));
    }

    @Test
    void update_rejectsAStaleForm() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String id = JsonPath.read(create("/api/v1/items", itemBody(supplierId, ingredientId, "kg", null)), "$.id");

        mockMvc.perform(authed(put("/api/v1/items/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"Arabica\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                                + "\"unit\":\"kg\",\"version\":99}").formatted(supplierId, ingredientId)))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_softDeletesAndFreesTheMappingForReuse() throws Exception {
        String ingredientId = ingredient("g");
        String supplierId = supplier();
        String id = JsonPath.read(create("/api/v1/items", itemBody(supplierId, ingredientId, "kg", null)), "$.id");

        mockMvc.perform(authed(delete("/api/v1/items/" + id))).andExpect(status().isNoContent());
        mockMvc.perform(authed(get("/api/v1/items/" + id))).andExpect(status().isNotFound());

        // A removal by mistake must not permanently reserve the supplier/ingredient/unit slot.
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(supplierId, ingredientId, "kg", null)))
                .andExpect(status().isCreated());
    }
}
