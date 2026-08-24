package vn.essvn.erpcafe.staff;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.staff.domain.Employee;
import vn.essvn.erpcafe.staff.domain.Shift;
import vn.essvn.erpcafe.staff.persistence.EmployeeRepository;
import vn.essvn.erpcafe.staff.persistence.ShiftRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on staff reads and writes (AUTH-G04, staff module).
 *
 * <p>Employees are company-scoped; shifts are branch-scoped. Reads by id,
 * clock actions, and scheduling must all refuse another tenant's rows. A
 * second company (with its own branch, employee, and shift) is seeded, then
 * exercised through the HTTP layer as the admin of company A.
 */
class StaffTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Instant START = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T16:00:00Z");

    @Autowired private CompanyRepository companyRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;

    private record Foreign(UUID employeeId, UUID shiftId) {
    }

    /** Seeds a company B with its own branch, employee, and a scheduled shift. */
    private Foreign seedForeign() {
        int n = SEQ.incrementAndGet();
        Company b = companyRepository.save(new Company("Staff Other %d".formatted(n), "STF%d".formatted(n)));
        Branch branchB = branchRepository.save(new Branch(b.getId(), "B Branch", "SFB%d".formatted(n)));
        Employee empB = employeeRepository.save(
                new Employee(b.getId(), "Foreign Emp %d".formatted(n), "Barista", Money.of(BigDecimal.TEN)));
        Shift shiftB = shiftRepository.save(new Shift(branchB.getId(), empB.getId(), START, END, "b shift"));
        return new Foreign(empB.getId(), shiftB.getId());
    }

    /** The admin's own active branch (company A), from the token's branch set. */
    private String ownBranch() throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<java.util.List<String>>read(me, "$.branchIds").get(0);
    }

    @Test
    void employeeGet_rejectsAnotherCompanysEmployee() throws Exception {
        UUID foreignEmp = seedForeign().employeeId();

        mockMvc.perform(get("/api/v1/staff/employees/" + foreignEmp)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shiftClockIn_rejectsAnotherBranchsShift() throws Exception {
        UUID foreignShift = seedForeign().shiftId();

        mockMvc.perform(post("/api/v1/staff/shifts/" + foreignShift + "/clock-in")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .header("X-Branch-Id", ownBranch()))
                .andExpect(status().isNotFound());
    }

    @Test
    void schedule_rejectsAnotherCompanysEmployee() throws Exception {
        UUID foreignEmp = seedForeign().employeeId();

        mockMvc.perform(post("/api/v1/staff/shifts")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .header("X-Branch-Id", ownBranch())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"%s\",\"scheduledStart\":\"%s\",\"scheduledEnd\":\"%s\"}"
                                .formatted(foreignEmp, START, END)))
                .andExpect(status().isNotFound());
    }
}
