package vn.essvn.erpcafe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

/**
 * Class: GlobalExceptionHandlerTest
 * Purpose: A constraint violation that slips past a pre-check is answered 409 CONFLICT, never 500.
 */
class GlobalExceptionHandlerTest {

    @Test
    void aLateForeignKeyViolation_isAConflictWithACode() {
        ProblemDetail problem = new GlobalExceptionHandler()
                .handleDataIntegrity(new DataIntegrityViolationException("fk_uba_role"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT");
        assertThat(problem.getDetail()).doesNotContain("fk_uba_role");
    }

    @Test
    void aBusinessRuleCannotShadowTheStandardProblemFields() {
        ProblemDetail problem = new GlobalExceptionHandler().handleBusinessRule(new BusinessRuleException("REAL_CODE", "Real detail",
                Map.of("code", "FAKE", "detail", "fake", "title", "fake", "status", 200, "type", "fake", "instance", "fake",
                        "shortfalls", "kept")));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getDetail()).isEqualTo("Real detail");
        assertThat(problem.getProperties()).containsEntry("code", "REAL_CODE").containsEntry("shortfalls", "kept");
        assertThat(problem.getProperties()).doesNotContainKeys("detail", "title", "status", "type", "instance");
    }
}
