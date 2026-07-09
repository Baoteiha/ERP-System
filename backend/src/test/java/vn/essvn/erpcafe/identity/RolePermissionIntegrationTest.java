package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/** Managing a role's permission set: replace, add, remove, plus guards. */
class RolePermissionIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private List<String> permsOf(String token, String roleId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/roles/" + roleId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.permissions");
    }

    @Test
    void replaceAddRemove_permissionsOnRole() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();

        // create a role with a single permission
        String created = mockMvc.perform(post("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Supervisor%d\",\"description\":\"d\",\"permissions\":[\"branch:read\"]}".formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(created, "$.id");
        assertThat(permsOf(token, roleId)).containsExactlyInAnyOrder("branch:read");

        // REPLACE → exactly the new set
        mockMvc.perform(put("/api/v1/roles/" + roleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"branch:read\",\"catalog:read\"]}"))
                .andExpect(status().isOk());
        assertThat(permsOf(token, roleId)).containsExactlyInAnyOrder("branch:read", "catalog:read");

        // ADD → union
        mockMvc.perform(post("/api/v1/roles/" + roleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"role:read\"]}"))
                .andExpect(status().isOk());
        assertThat(permsOf(token, roleId)).containsExactlyInAnyOrder("branch:read", "catalog:read", "role:read");

        // REMOVE → one gone
        mockMvc.perform(delete("/api/v1/roles/" + roleId + "/permissions/branch:read")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andExpect(status().isOk());
        assertThat(permsOf(token, roleId)).containsExactlyInAnyOrder("catalog:read", "role:read");
    }

    @Test
    void replacePermissions_rejectsUnknownPermission() throws Exception {
        String token = adminToken();
        int n = SEQ.incrementAndGet();
        String roleId = JsonPath.read(mockMvc.perform(post("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temp%d\",\"description\":\"d\",\"permissions\":[\"branch:read\"]}".formatted(n)))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/v1/roles/" + roleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"does:notexist\"]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void editingOwnerRole_isRejected() throws Exception {
        String token = adminToken();
        String rolesJson = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        String ownerRoleId = ((List<String>) JsonPath.read(rolesJson, "$[?(@.name=='OWNER')].id")).get(0);

        mockMvc.perform(put("/api/v1/roles/" + ownerRoleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"branch:read\"]}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
