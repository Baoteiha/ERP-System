package vn.essvn.erpcafe.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.staff.api.StaffApi;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.domain.ShiftStatus;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PinClockTest
 * Purpose: Pins PIN-protected clocking and the tenant-safe, batchable labor cost of the StaffApi.
 */
class PinClockTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private StaffApi staffApi;
    @Autowired private OrganizationApi organizationApi;

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

    private UUID employee() throws Exception {
        String body = mockMvc.perform(authed(post("/api/v1/staff/employees"))
                        .content("{\"fullName\":\"Pin %d\",\"hourlyRate\":30000}".formatted(SEQ.incrementAndGet())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID shift(UUID employeeId) throws Exception {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        String body = mockMvc.perform(authed(post("/api/v1/staff/shifts"))
                        .content("{\"employeeId\":\"%s\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"}"
                                .formatted(employeeId, start, start.plus(8, ChronoUnit.HOURS))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void setPin(UUID employeeId, String pin) throws Exception {
        mockMvc.perform(authed(put("/api/v1/staff/employees/" + employeeId + "/pin"))
                        .content("{\"pin\":\"%s\"}".formatted(pin)))
                .andExpect(status().isNoContent());
    }

    private MockHttpServletRequestBuilder punch(UUID shiftId, String action, String pin) {
        return authed(post("/api/v1/staff/shifts/" + shiftId + "/" + action)).content("{\"pin\":\"%s\"}".formatted(pin));
    }

    @Test
    void clockIn_withTheWrongPin_isForbiddenAndLeavesTheShiftUntouched() throws Exception {
        login();
        UUID emp = employee();
        setPin(emp, "1234");
        UUID shift = shift(emp);

        mockMvc.perform(punch(shift, "clock-in", "0000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + shift + "/clock-in")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(storedShift(shift).getStatus()).isEqualTo(ShiftStatus.SCHEDULED);
    }

    @Test
    void clockInAndOut_withTheRightPin_work() throws Exception {
        login();
        UUID emp = employee();
        setPin(emp, "1234");
        UUID shift = shift(emp);

        mockMvc.perform(punch(shift, "clock-in", "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(punch(shift, "clock-out", "9999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(storedShift(shift).getStatus()).isEqualTo(ShiftStatus.IN_PROGRESS);
        mockMvc.perform(punch(shift, "clock-out", "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void anEmployeeWithoutAPin_canStillClockWithoutOne() throws Exception {
        login();
        UUID shift = shift(employee());

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + shift + "/clock-in")))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + shift + "/clock-out")))
                .andExpect(status().isOk());
    }

    @Test
    void fiveWrongPins_lockTheEmployee_evenForTheCorrectPin() throws Exception {
        login();
        UUID emp = employee();
        setPin(emp, "1234");
        UUID shift = shift(emp);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(punch(shift, "clock-in", "0000")).andExpect(status().isForbidden());
        }

        mockMvc.perform(punch(shift, "clock-in", "1234"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        assertThat(storedShift(shift).getStatus()).isEqualTo(ShiftStatus.SCHEDULED);
    }

    @Test
    void aCorrectPin_resetsTheCounter() throws Exception {
        login();
        UUID emp = employee();
        setPin(emp, "1234");
        UUID shift = shift(emp);
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(punch(shift, "clock-in", "0000")).andExpect(status().isForbidden());
        }
        mockMvc.perform(punch(shift, "clock-in", "1234")).andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(punch(shift, "clock-out", "0000")).andExpect(status().isForbidden());
        }
        mockMvc.perform(punch(shift, "clock-out", "1234")).andExpect(status().isOk());
    }

    @Test
    void lockingOneEmployee_leavesAnotherUnaffected() throws Exception {
        login();
        UUID locked = employee();
        setPin(locked, "1234");
        UUID lockedShift = shift(locked);
        UUID other = employee();
        setPin(other, "5678");
        UUID otherShift = shift(other);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(punch(lockedShift, "clock-in", "0000")).andExpect(status().isForbidden());
        }

        mockMvc.perform(punch(otherShift, "clock-in", "5678")).andExpect(status().isOk());
    }

    @Test
    void employeeAndShiftResponses_sayWhetherAPinIsSet_butNeverTheHash() throws Exception {
        login();
        UUID emp = employee();
        UUID shift = shift(emp);
        mockMvc.perform(authed(get("/api/v1/staff/employees/" + emp)))
                .andExpect(jsonPath("$.hasPin").value(false));
        mockMvc.perform(authed(get("/api/v1/staff/shifts/open"))).andExpect(status().isOk());

        setPin(emp, "1234");

        mockMvc.perform(authed(get("/api/v1/staff/employees/" + emp)))
                .andExpect(jsonPath("$.hasPin").value(true))
                .andExpect(jsonPath("$.pinHash").doesNotExist());
        mockMvc.perform(authed(get("/api/v1/staff/shifts")
                        .param("from", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                        .param("to", Instant.now().plus(1, ChronoUnit.DAYS).toString())))
                .andExpect(jsonPath("$[?(@.id=='%s')].employeeHasPin".formatted(shift)).value(true));
    }

    @Test
    void managerAdjust_doesNotNeedThePin() throws Exception {
        login();
        UUID emp = employee();
        setPin(emp, "1234");
        UUID shift = shift(emp);
        Instant in = Instant.now().minus(3, ChronoUnit.HOURS);

        mockMvc.perform(punch(shift, "clock-in", "1234")).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + shift + "/adjust"))
                        .content("{\"clockIn\":\"%s\",\"clockOut\":\"%s\"}".formatted(in, in.plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isOk());
    }

    @Test
    void setPin_rejectsAnythingButFourToSixDigits_andNeverEchoesOrStoresThePlainPin() throws Exception {
        login();
        UUID emp = employee();
        for (String bad : new String[] { "123", "1234567", "12a4", "" }) {
            mockMvc.perform(authed(put("/api/v1/staff/employees/" + emp + "/pin"))
                            .content("{\"pin\":\"%s\"}".formatted(bad)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(storedEmployee(emp).hasPin()).isFalse();

        setPin(emp, "246810");

        String stored = storedEmployee(emp).getPinHash();
        assertThat(stored).isNotEqualTo("246810").startsWith("$2");
        mockMvc.perform(authed(get("/api/v1/staff/employees/" + emp)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("246810"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(stored))));
    }

    @Test
    void setPin_onAnotherCompanysEmployee_is404AndChangesNothing() throws Exception {
        login();
        Foreign foreign = seedForeign();

        mockMvc.perform(authed(put("/api/v1/staff/employees/" + foreign.employeeId() + "/pin"))
                        .content("{\"pin\":\"1234\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(storedEmployee(foreign.employeeId()).hasPin()).isFalse();
    }

    @Test
    void laborCost_forAnotherCompanysBranch_isNotFound() throws Exception {
        login();
        UUID myCompany = TenantContext.runAsSystem(() -> organizationApi.companyIdOf(UUID.fromString(branchId)))
                .orElseThrow();
        Foreign foreign = seedForeign();
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant to = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> TenantContext.runAs(myCompany,
                () -> staffApi.laborCost(myCompany, foreign.branchIds().get(0), from, to)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> TenantContext.runAs(myCompany,
                () -> staffApi.laborCostByBranch(myCompany, foreign.branchIds(), from, to)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void laborCostByBranch_equalsThePerBranchSums() throws Exception {
        Foreign foreign = seedForeign();
        Instant now = Instant.now();
        completed(foreign, 0, now.minus(5, ChronoUnit.HOURS), 2, "30000");
        completed(foreign, 0, now.minus(3, ChronoUnit.HOURS), 1, "20000");
        completed(foreign, 1, now.minus(4, ChronoUnit.HOURS), 3, "10000");
        // outside the window: clocked out two days ago
        completed(foreign, 0, now.minus(48, ChronoUnit.HOURS), 1, "99999");
        Instant from = now.minus(1, ChronoUnit.DAYS);

        Map<UUID, BigDecimal> batch = TenantContext.runAs(foreign.companyId(),
                () -> staffApi.laborCostByBranch(foreign.companyId(), foreign.branchIds(), from, now));

        assertThat(batch).hasSize(2);
        for (UUID b : foreign.branchIds()) {
            assertThat(batch.get(b)).isEqualByComparingTo(TenantContext.runAs(foreign.companyId(),
                    () -> staffApi.laborCost(foreign.companyId(), b, from, now)));
        }
        assertThat(batch.get(foreign.branchIds().get(0))).isEqualByComparingTo("80000");
        assertThat(batch.get(foreign.branchIds().get(1))).isEqualByComparingTo("30000");
    }

    private Shift storedShift(UUID id) {
        return TenantContext.runAsSystem(() -> shiftRepository.findById(id).orElseThrow());
    }

    private Employee storedEmployee(UUID id) {
        return TenantContext.runAsSystem(() -> employeeRepository.findById(id).orElseThrow());
    }

    private void completed(Foreign f, int branchIdx, Instant clockOut, int hours, String rate) {
        Shift s = new Shift(f.branchIds().get(branchIdx), f.employeeId(),
                clockOut.minus(hours + 1, ChronoUnit.HOURS), clockOut, null);
        s.clockIn(clockOut.minus(hours, ChronoUnit.HOURS), Money.of(new BigDecimal(rate)));
        s.clockOut(clockOut, Duration.ofHours(16));
        TenantContext.runAs(f.companyId(), () -> shiftRepository.save(s));
    }

    private record Foreign(UUID companyId, List<UUID> branchIds, UUID employeeId) {
    }

    private Foreign seedForeign() {
        int n = SEQ.incrementAndGet();
        Company c = companyRepository.save(new Company("Pin Other %d".formatted(n), "PIN%d".formatted(n)));
        return TenantContext.runAs(c.getId(), () -> {
            Branch b1 = branchRepository.save(new Branch("P1", "PA%d".formatted(n)));
            Branch b2 = branchRepository.save(new Branch("P2", "PB%d".formatted(n)));
            Employee e = employeeRepository.save(
                    new Employee("Pin Foreign %d".formatted(n), "Barista", Money.of(BigDecimal.TEN)));
            return new Foreign(c.getId(), List.of(b1.getId(), b2.getId()), e.getId());
        });
    }
}
