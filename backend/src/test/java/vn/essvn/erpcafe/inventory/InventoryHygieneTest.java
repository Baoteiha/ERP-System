package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Inventory input hygiene (plan task A4): blank SKUs are stored as null rather
 * than colliding as empty strings; an item's supplier/ingredient cannot be
 * silently "changed" by an update that would be ignored; an ingredient with
 * live items cannot be deleted out from under them.
 */
class InventoryHygieneTest extends AbstractIntegrationTest {

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

    private record Fixture(String supplierId, String ingredientId) {
    }

    private Fixture fixture(int n) throws Exception {
        String ingredientId = JsonPath.read(create("/api/v1/ingredients",
                "{\"name\":\"HygBeans%d\",\"baseUnit\":\"g\"}".formatted(n)), "$.id");
        String supplierId = JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"HygSupplier%d\"}".formatted(n)), "$.id");
        return new Fixture(supplierId, ingredientId);
    }

    private String itemBody(Fixture f, String sku, String name, String unit) {
        return "{\"sku\":%s,\"name\":\"%s\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"%s\"}"
                .formatted(sku == null ? "null" : "\"" + sku + "\"", name, f.supplierId(), f.ingredientId(), unit);
    }

    @Test
    void blankSku_isStoredAsNull_soSkulessItemsNeverCollide() throws Exception {
        Fixture f = fixture(SEQ.incrementAndGet());

        // Two SKU-less items for the same supplier: previously '' = '' violated the
        // partial unique index and surfaced as a 500 on the second create.
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(f, "   ", "Bag 1kg", "kg")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").doesNotExist());
        mockMvc.perform(authed(post("/api/v1/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(f, "", "Loose grams", "g")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").doesNotExist());
    }

    @Test
    void update_rejectsRepointingSupplierOrIngredient() throws Exception {
        int n = SEQ.incrementAndGet();
        Fixture f = fixture(n);
        String otherSupplierId = JsonPath.read(create("/api/v1/suppliers",
                "{\"name\":\"HygOther%d\"}".formatted(n)), "$.id");

        String created = create("/api/v1/items", itemBody(f, "SKU-" + n, "Bag", "kg"));
        String itemId = JsonPath.read(created, "$.id");
        Integer version = JsonPath.read(created, "$.version");

        // Repointing the supplier must be refused loudly, not accepted-and-ignored.
        mockMvc.perform(authed(put("/api/v1/items/" + itemId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-%d\",\"name\":\"Bag\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"kg\",\"version\":%d}"
                                .formatted(n, otherSupplierId, f.ingredientId(), version)))
                .andExpect(status().isConflict());
    }

    @Test
    void ingredientDelete_isBlockedWhileItemsReferenceIt() throws Exception {
        Fixture f = fixture(SEQ.incrementAndGet());
        String itemId = JsonPath.read(create("/api/v1/items", itemBody(f, null, "Bag", "kg")), "$.id");

        // With a live item pointing at it, deleting the ingredient would orphan the item
        // into a misleading 404 on its next edit — refuse with 409 instead.
        mockMvc.perform(authed(delete("/api/v1/ingredients/" + f.ingredientId())))
                .andExpect(status().isConflict());

        // Once the item is gone, the delete goes through.
        mockMvc.perform(authed(delete("/api/v1/items/" + itemId))).andExpect(status().isNoContent());
        mockMvc.perform(authed(delete("/api/v1/ingredients/" + f.ingredientId())))
                .andExpect(status().isNoContent());
    }
}
