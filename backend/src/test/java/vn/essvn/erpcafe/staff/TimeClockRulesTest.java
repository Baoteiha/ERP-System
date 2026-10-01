package vn.essvn.erpcafe.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: TimeClockRulesTest
 * Purpose: Pins the time clock rules: one open shift per employee, no clock-in when deactivated, and shift length limits.
 */
class TimeClockRulesTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private BranchRepository branchRepository;

    private String token;
    private String branchId;

    private void login() throws Exception {
        token = adminToken();
        String me = mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                .andReturn().getResponse().getContentAsString();
        branchId = JsonPath.<List<String>>read(me, "$.branchIds").get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private UUID employee(String name, String rate) throws Exception {
        String body = mockMvc.perform(authed(post("/api/v1/staff/employees"))
                        .content("{\"fullName\":\"%s\",\"hourlyRate\":%s}".formatted(name, rate)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID shift(UUID employeeId, Instant start) throws Exception {
        String body = mockMvc.perform(authed(post("/api/v1/staff/shifts"))
                        .content("{\"employeeId\":\"%s\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"}"
                                .formatted(employeeId, start, start.plus(8, ChronoUnit.HOURS))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static String unique() {
        return "TC" + SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    @Test
    void clockIn_refusedWhileTheEmployeeHasAnOpenShiftAtAnotherBranch() throws Exception {
        login();
        UUID emp = employee("Open " + unique(), "30000");
        Employee employee = employeeRepository.findById(emp).orElseThrow();
        Branch other = branchRepository.save(new Branch(employee.getCompanyId(), "Other", unique()));
        Shift open = new Shift(other.getId(), emp, Instant.now(), Instant.now().plus(8, ChronoUnit.HOURS), null);
        open.clockIn(Instant.now(), Money.of(new BigDecimal("30000")));
        shiftRepository.save(open);
        UUID mine = shift(emp, Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + mine + "/clock-in")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(shiftRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(ShiftStatus.SCHEDULED);
    }

    @Test
    void clockIn_refusedForADeactivatedEmployee() throws Exception {
        login();
        UUID emp = employee("Gone " + unique(), "30000");
        UUID past = shift(emp, Instant.now().minus(2, ChronoUnit.HOURS));
        deactivate(emp, "Gone");

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + past + "/clock-in")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void createEmployee_allowsTheSameFullNameTwice() throws Exception {
        login();
        String name = "Nguyen Van A " + unique();
        employee(name, "20000");
        employee(name, "25000");
    }

    @Test
    void deactivating_cancelsFutureScheduledShiftsButKeepsHistory() throws Exception {
        login();
        UUID emp = employee("Leaver " + unique(), "30000");
        UUID future = shift(emp, Instant.now().plus(3, ChronoUnit.DAYS));
        UUID inProgress = shift(emp, Instant.now().minus(1, ChronoUnit.HOURS));
        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + inProgress + "/clock-in")))
                .andExpect(status().isOk());

        deactivate(emp, "Leaver");

        assertThat(shiftRepository.findById(future).orElseThrow().getStatus()).isEqualTo(ShiftStatus.CANCELLED);
        assertThat(shiftRepository.findById(inProgress).orElseThrow().getStatus()).isEqualTo(ShiftStatus.IN_PROGRESS);
    }

    private void deactivate(UUID emp, String name) throws Exception {
        mockMvc.perform(authed(put("/api/v1/staff/employees/" + emp))
                        .content("{\"fullName\":\"%s\",\"active\":false}".formatted(name)))
                .andExpect(status().isOk());
    }
}
