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

import vn.essvn.erpcafe.common.context.TenantContext;
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
        Employee employee = TenantContext.runAsSystem(() -> employeeRepository.findById(emp).orElseThrow());
        TenantContext.runAs(employee.getCompanyId(), () -> {
            Branch other = branchRepository.save(new Branch("Other", unique()));
            Shift open = new Shift(employee.getCompanyId(), other.getId(), emp, Instant.now(), Instant.now().plus(8, ChronoUnit.HOURS), null);
            open.clockIn(Instant.now(), Money.of(new BigDecimal("30000")));
            shiftRepository.save(open);
        });
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

    /** A shift already running for {@code hours}, saved straight through the repository. */
    private UUID openShift(UUID emp, String rate, long hours) throws Exception {
        UUID id = shift(emp, Instant.now().minus(hours + 1, ChronoUnit.HOURS));
        Shift s = shiftRepository.findById(id).orElseThrow();
        s.clockIn(Instant.now().minus(hours, ChronoUnit.HOURS), Money.of(new BigDecimal(rate)));
        shiftRepository.save(s);
        return id;
    }

    @Test
    void clockOut_refusedAfterMoreThanSixteenHours() throws Exception {
        login();
        UUID emp = employee("Forgot " + unique(), "30000");
        UUID id = openShift(emp, "30000", 17);

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/clock-out")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.detail").value("Shift is longer than 16 hours; ask a manager to adjust it"));

        assertThat(shiftRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ShiftStatus.IN_PROGRESS);
    }

    @Test
    void adjust_setsClockOutCompletesTheShiftAndCostsItFromTheSnapshotRate() throws Exception {
        login();
        UUID emp = employee("Adjusted " + unique(), "30000");
        UUID id = openShift(emp, "30001", 17);
        Instant in = shiftRepository.findById(id).orElseThrow().getClockInAt();
        Instant out = in.plus(8 * 60 + 20, ChronoUnit.MINUTES);

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/adjust"))
                        .content("{\"clockOut\":\"%s\"}".formatted(out)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.clockOutAt").exists());

        Shift saved = shiftRepository.findById(id).orElseThrow();
        // 30001 x 8h20m = 250008.3333... rounded once at the Money scale
        assertThat(saved.getLaborCost().getAmount()).isEqualByComparingTo("250008.3333");
        assertThat(saved.getClockOutAt()).isEqualTo(out);
        assertThat(saved.getClockInAt()).isEqualTo(in);
    }

    @Test
    void adjust_recordsASeventeenHourShiftButRefusesTwentyFiveHoursAndTheFuture() throws Exception {
        login();
        UUID emp = employee("Double " + unique(), "30000");
        UUID id = openShift(emp, "30000", 30);
        Instant in = shiftRepository.findById(id).orElseThrow().getClockInAt();

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/adjust"))
                        .content("{\"clockOut\":\"%s\"}".formatted(in.plus(25, ChronoUnit.HOURS))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/adjust"))
                        .content("{\"clockOut\":\"%s\"}".formatted(Instant.now().plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(shiftRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ShiftStatus.IN_PROGRESS);

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/adjust"))
                        .content("{\"clockOut\":\"%s\"}".formatted(in.plus(17, ChronoUnit.HOURS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(shiftRepository.findById(id).orElseThrow().getLaborCost().getAmount())
                .isEqualByComparingTo("510000");
    }

    @Test
    void adjust_refusedWhenTheShiftIsStillScheduled() throws Exception {
        login();
        UUID emp = employee("Sched " + unique(), "30000");
        UUID id = shift(emp, Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(authed(post("/api/v1/staff/shifts/" + id + "/adjust"))
                        .content("{\"clockOut\":\"%s\"}".formatted(Instant.now())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void list_rejectsAWindowThatEndsBeforeItStarts() throws Exception {
        login();
        mockMvc.perform(authed(get("/api/v1/staff/shifts"))
                        .param("from", "2026-02-02T00:00:00Z").param("to", "2026-02-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void openShifts_listsTheActiveBranchsRunningShiftsOnly() throws Exception {
        login();
        UUID emp = employee("Running " + unique(), "30000");
        UUID running = openShift(emp, "30000", 2);
        UUID scheduled = shift(employee("Waiting " + unique(), "30000"), Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(authed(get("/api/v1/staff/shifts/open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(running)).exists())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(scheduled)).doesNotExist());
    }

    private void deactivate(UUID emp, String name) throws Exception {
        mockMvc.perform(authed(put("/api/v1/staff/employees/" + emp))
                        .content("{\"fullName\":\"%s\",\"active\":false}".formatted(name)))
                .andExpect(status().isOk());
    }
}
