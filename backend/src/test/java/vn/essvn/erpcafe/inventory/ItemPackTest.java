package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ItemPackTest
 * Purpose: An item may declare one pack, both halves or neither, and a box and loose pieces are two ways of buying.
 */
class ItemPackTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;

    @BeforeEach
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }

    private String straws() throws Exception {
        return id(send(post("/api/v1/ingredients"),
                "{\"name\":\"PackStraws%d\",\"baseUnit\":\"pc\"}".formatted(SEQ.incrementAndGet())));
    }

    private String supplier() throws Exception {
        return id(send(post("/api/v1/suppliers"), "{\"name\":\"PackShop%d\"}".formatted(SEQ.incrementAndGet())));
    }

    private String itemBody(String supplierId, String ingredientId, String pack) {
        return "{\"name\":\"Ống hút\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"pc\"%s}"
                .formatted(supplierId, ingredientId, pack);
    }

    @Test
    void itemWithAPack_keepsBothHalves() throws Exception {
        send(post("/api/v1/items"), itemBody(supplier(), straws(), ",\"packLabel\":\" hộp \",\"packSize\":100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packLabel").value("hộp"))
                .andExpect(jsonPath("$.packSize").value(100.0));
    }

    @Test
    void halfAPack_isRefused() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        for (String pack : List.of(",\"packLabel\":\"hộp\"", ",\"packSize\":100",
                ",\"packLabel\":\"hộp\",\"packSize\":0", ",\"packLabel\":\"  \",\"packSize\":100")) {
            send(post("/api/v1/items"), itemBody(supplierId, ingredientId, pack))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("both a name and a size above 0")));
        }
    }

    @Test
    void aBoxAndLoosePieces_areTwoWaysOfBuying_butTheSameBoxTwiceIsNot() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        id(send(post("/api/v1/items"), itemBody(supplierId, ingredientId, "")));
        id(send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":100")));

        send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ",\"packLabel\":\"HỘP\",\"packSize\":100"))
                .andExpect(status().isConflict());
        send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ""))
                .andExpect(status().isConflict());
    }

    @Test
    void editingAPack_changesIt_andClearingBothHalvesRemovesIt() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        String itemId = id(send(post("/api/v1/items"),
                itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":100")));

        send(put("/api/v1/items/" + itemId),
                itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":50,\"version\":0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packSize").value(50.0));
        send(put("/api/v1/items/" + itemId), itemBody(supplierId, ingredientId, ",\"version\":1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packLabel").doesNotExist());

        mockMvc.perform(authed(get("/api/v1/items/" + itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packSize").doesNotExist());
    }
}
