package vn.essvn.erpcafe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
}
