package vn.essvn.erpcafe.identity.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.essvn.erpcafe.identity.domain.AuthEvent;
import vn.essvn.erpcafe.identity.domain.AuthEventType;

/**
 * Class: AuthEventStore
 * Purpose: Appends to the auth_event audit trail and pages through one company's entries, newest first.
 *
 * <p>SQL rather than an entity: a row is never updated, and a failed sign-in for an unknown email is
 * written with no company, which a tenant-owned entity cannot be.
 */
@Component
public class AuthEventStore {

    private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, user_id, type, ip, occurred_at, detail::text AS detail";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AuthEventStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Written in the caller's transaction, so the event exists exactly when the change it records does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID companyId, UUID userId, AuthEventType type, String ip, Map<String, Object> detail) {
        jdbc.update("INSERT INTO auth_event (id, company_id, user_id, type, ip, detail)"
                + " VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), companyId, userId, type.name(), ip, json.writeValueAsString(detail));
    }

    /** The company's entries, or one user's among them when {@code userId} is given. */
    @Transactional(readOnly = true)
    public Page<AuthEvent> page(UUID companyId, UUID userId, Pageable pageable) {
        List<Object> args = new ArrayList<>(List.of(companyId));
        String where = " WHERE company_id = ?";
        if (userId != null) {
            where += " AND user_id = ?";
            args.add(userId);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM auth_event" + where, Long.class, args.toArray());
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<AuthEvent> content = jdbc.query("SELECT " + COLUMNS + " FROM auth_event" + where
                + " ORDER BY occurred_at DESC, id LIMIT ? OFFSET ?", this::toEvent, args.toArray());
        return new PageImpl<>(content, pageable, total);
    }

    private AuthEvent toEvent(ResultSet rs, int row) throws SQLException {
        return new AuthEvent(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                AuthEventType.valueOf(rs.getString("type")), rs.getString("ip"),
                rs.getTimestamp("occurred_at").toInstant(), json.readValue(rs.getString("detail"), DETAIL));
    }
}
