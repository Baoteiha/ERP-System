package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Role rename and delete (AUTH-G17), including the guards that keep the system
 * usable: the seeded default roles are matched by name at startup, and a role
 * somebody still holds cannot be removed out from under them.
 */
class RoleLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserBranchAccessRepository accessRepository;

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
    void deleteWaitsForTheRowLock_soAGrantInsertedMeanwhileIsSeen() throws Exception {
        String roleId = createRole();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        UUID companyId = UUID.fromString(JsonPath.read(me, "$.companyId"));
        UUID branchId = UUID.fromString(((List<String>) JsonPath.read(me, "$.branchIds")).get(0));
        int n = SEQ.incrementAndGet();
        UUID userId = UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rolelock%d@esscafe.vn\",\"password\":\"password123\",\"fullName\":\"Role Lock\"}"
                                .formatted(n)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch deleteIssued = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A grant of the role is in flight: it holds the role's row lock and commits after the delete began.
            Future<?> grant = pool.submit(() -> TenantContext.runAs(companyId,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                        roleRepository.lockByIdAndCompanyId(UUID.fromString(roleId), companyId).orElseThrow();
                        locked.countDown();
                        await(deleteIssued);
                        accessRepository.saveAndFlush(new UserBranchAccess(userId, branchId, UUID.fromString(roleId)));
                    })));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> delete = pool.submit(() -> mockMvc.perform(delete("/api/v1/roles/" + roleId)
                            .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(500);
            assertThat(delete.isDone()).as("delete waits for the lock").isFalse();
            deleteIssued.countDown();
            grant.get(10, TimeUnit.SECONDS);

            // It saw the grant the other transaction committed: refused cleanly, not a 500.
            assertThat(delete.get(10, TimeUnit.SECONDS)).isEqualTo(422);
            assertThat(TenantContext.runAs(companyId, () -> roleRepository.existsById(UUID.fromString(roleId))))
                    .isTrue();
        } finally {
            pool.shutdownNow();
            TenantContext.runAs(companyId, () -> accessRepository.findByRoleId(UUID.fromString(roleId))
                    .forEach(accessRepository::delete));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void deletingARoleStillGrantedToSomeone_isRejected() throws Exception {
        // OWNER is both a default role and one the bootstrap admin holds.
        mockMvc.perform(delete("/api/v1/roles/" + roleIdByName("OWNER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isUnprocessableEntity());
    }
}
