package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Role rename and delete (AUTH-G17), including the guards that keep the system
 * usable: the seeded default roles are matched by name at startup, and a role
 * somebody still holds cannot be removed out from under them.
 */
class RoleLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String createRole() throws Exception {
        int n = SEQ.incrementAndGet();
        String json = mockMvc.perform(post("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"BARISTA%d","description":"Bar staff","permissions":["sales:read"]}
                                """.formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String roleIdByName(String name) throws Exception {
        String json = mockMvc.perform(get("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$[?(@.name == '%s')].id".formatted(name));
        return ids.get(0);
    }

    @Test
    void renameCustomRole_succeeds() throws Exception {
        String id = createRole();

        mockMvc.perform(put("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BAR_LEAD%d\",\"description\":\"Lead barista\"}"
                                .formatted(SEQ.incrementAndGet())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Lead barista"));
    }

    @Test
    void deleteUnusedCustomRole_succeeds() throws Exception {
        String id = createRole();

        mockMvc.perform(delete("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamingADefaultRole_isRejected() throws Exception {
        mockMvc.perform(put("/api/v1/roles/" + roleIdByName("MANAGER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SUPERVISOR\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletingADefaultRole_isRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/roles/" + roleIdByName("CASHIER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletingARoleStillGrantedToSomeone_isRejected() throws Exception {
        // OWNER is both a default role and one the bootstrap admin holds.
        mockMvc.perform(delete("/api/v1/roles/" + roleIdByName("OWNER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isUnprocessableEntity());
    }
}
