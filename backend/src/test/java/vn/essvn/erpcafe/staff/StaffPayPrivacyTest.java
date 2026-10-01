package vn.essvn.erpcafe.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: StaffPayPrivacyTest
 * Purpose: Proves pay data (rates, contact details, labour cost) reaches only callers holding staff:write.
 */
class StaffPayPrivacyTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private ShiftRepository shiftRepository;

    private String adminToken;
    private String branchId;
    private String cashierToken;

    private void setUp() throws Exception {
        adminToken = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andReturn().getResponse().getContentAsString();
        branchId = JsonPath.<List<String>>read(me, "$.branchIds").get(0);

        // A fresh cashier: the admin's own grants are shared seed data and must not change.
        String email = "pay-cashier-%d@esscafe.vn".formatted(SEQ.incrementAndGet());
        String userId = JsonPath.read(admin(post("/api/v1/users"),
                "{\"email\":\"%s\",\"password\":\"cashier123\",\"fullName\":\"Cashier\"}".formatted(email)), "$.id");
        String roles = mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.<List<String>>read(roles, "$[?(@.name=='CASHIER')].id").get(0);
        admin(post("/api/v1/users/" + userId + "/access"),
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branchId, roleId));
        cashierToken = accessToken(email, "cashier123");
    }

    private String admin(MockHttpServletRequestBuilder b, String json)
            throws Exception {
        return mockMvc.perform(b.header(HttpHeaders.AUTHORIZATION, authHeader(adminToken))
                        .header("X-Branch-Id", branchId).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void cashierSeesNoPayDataButAdminDoes() throws Exception {
        setUp();
        String name = "Paid " + System.nanoTime();
        String created = admin(post("/api/v1/staff/employees"),
                "{\"fullName\":\"%s\",\"hourlyRate\":45000,\"phone\":\"0901234567\",\"email\":\"p@x.vn\"}".formatted(name));
        UUID emp = UUID.fromString(JsonPath.read(created, "$.id"));
        Instant in = Instant.now().minus(5, ChronoUnit.HOURS);
        Shift shift = new Shift(UUID.fromString(branchId), emp, in, in.plus(4, ChronoUnit.HOURS), null);
        shift.clockIn(in, Money.of(new BigDecimal("45000")));
        shift.clockOut(in.plus(4, ChronoUnit.HOURS), Duration.ofHours(16));
        shift = shiftRepository.save(shift);
        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.COMPLETED);

        String employeeFilter = "$[?(@.id=='%s')]".formatted(emp);
        String shiftFilter = "$[?(@.id=='%s')]".formatted(shift.getId());
        String from = in.minus(1, ChronoUnit.DAYS).toString();
        String to = in.plus(1, ChronoUnit.DAYS).toString();

        mockMvc.perform(get("/api/v1/staff/employees").header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(employeeFilter + ".fullName").value(name))
                .andExpect(jsonPath(employeeFilter + ".hourlyRate").doesNotExist())
                .andExpect(jsonPath(employeeFilter + ".phone").doesNotExist())
                .andExpect(jsonPath(employeeFilter + ".email").doesNotExist());
        mockMvc.perform(get("/api/v1/staff/employees/" + emp).header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourlyRate").doesNotExist());
        mockMvc.perform(get("/api/v1/staff/shifts").param("from", from).param("to", to)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath(shiftFilter + ".status").value("COMPLETED"))
                .andExpect(jsonPath(shiftFilter + ".laborCost").doesNotExist())
                .andExpect(jsonPath(shiftFilter + ".hourlyRate").doesNotExist());

        mockMvc.perform(get("/api/v1/staff/employees").header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)))
                .andExpect(jsonPath(employeeFilter + ".hourlyRate.amount").exists())
                .andExpect(jsonPath(employeeFilter + ".phone").value("0901234567"))
                .andExpect(jsonPath(employeeFilter + ".email").value("p@x.vn"));
        mockMvc.perform(get("/api/v1/staff/shifts").param("from", from).param("to", to)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken)).header("X-Branch-Id", branchId))
                .andExpect(jsonPath(shiftFilter + ".laborCost.amount").exists());
    }

    @Test
    void cashierClockResponsesAndOpenListCarryNoPayData() throws Exception {
        setUp();
        UUID emp = UUID.fromString(JsonPath.read(admin(post("/api/v1/staff/employees"),
                "{\"fullName\":\"Clock %d\",\"hourlyRate\":45000}".formatted(System.nanoTime())), "$.id"));
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Shift shift = shiftRepository.save(
                new Shift(UUID.fromString(branchId), emp, start, start.plus(8, ChronoUnit.HOURS), null));
        String base = "/api/v1/staff/shifts/" + shift.getId();

        mockMvc.perform(post(base + "/clock-in")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourlyRate").doesNotExist())
                .andExpect(jsonPath("$.laborCost").doesNotExist());
        mockMvc.perform(get("/api/v1/staff/shifts/open")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')].status".formatted(shift.getId())).value("IN_PROGRESS"))
                .andExpect(jsonPath("$[?(@.id=='%s')].hourlyRate".formatted(shift.getId())).doesNotExist())
                .andExpect(jsonPath("$[?(@.id=='%s')].laborCost".formatted(shift.getId())).doesNotExist());
        mockMvc.perform(post(base + "/clock-out")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.hourlyRate").doesNotExist())
                .andExpect(jsonPath("$.laborCost").doesNotExist());
    }

    @Test
    void cashierCannotAdjustAShift() throws Exception {
        setUp();
        UUID emp = UUID.fromString(JsonPath.read(admin(post("/api/v1/staff/employees"),
                "{\"fullName\":\"Adj %d\"}".formatted(System.nanoTime())), "$.id"));
        Instant in = Instant.now().minus(3, ChronoUnit.HOURS);
        Shift open = new Shift(UUID.fromString(branchId), emp, in, in.plus(8, ChronoUnit.HOURS), null);
        open.clockIn(in, Money.zero());
        open = shiftRepository.save(open);

        mockMvc.perform(post("/api/v1/staff/shifts/" + open.getId() + "/adjust")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cashierToken)).header("X-Branch-Id", branchId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clockOut\":\"%s\"}".formatted(Instant.now())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(shiftRepository.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(ShiftStatus.IN_PROGRESS);
    }
}
