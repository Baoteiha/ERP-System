package vn.essvn.erpcafe.identity.application;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class: PasswordResetLinks
 * Purpose: Closes the password-reset links still open for users whose access or email has changed.
 */
@Component
class PasswordResetLinks {

    private final JdbcTemplate jdbc;

    PasswordResetLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // in the transaction of the change, as the app (row-level security keeps it to the company) or the system
    @Transactional(propagation = Propagation.MANDATORY)
    void closeOpen(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        String ids = String.join(", ", Collections.nCopies(userIds.size(), "?"));
        jdbc.update("UPDATE password_reset_token SET used_at = now() WHERE user_id IN (" + ids + ")"
                + " AND used_at IS NULL", userIds.toArray());
    }
}
