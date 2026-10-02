package vn.essvn.erpcafe.identity.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.identity.domain.AuthEvent;
import vn.essvn.erpcafe.identity.domain.AuthEventType;
import vn.essvn.erpcafe.identity.persistence.AuthEventStore;

/**
 * Class: AuthEventLog
 * Purpose: Records authentication events on the audit trail, with the client address, and reads it back.
 */
@Component
public class AuthEventLog {

    private final AuthEventStore store;

    AuthEventLog(AuthEventStore store) {
        this.store = store;
    }

    /**
     * Records, inside the caller's transaction, an event about {@code userId} of {@code companyId} that the
     * user brought about themselves: a sign-in (both null when it named no account), a refresh, a sign-out,
     * their own password change. No actor is named, whatever access token the request happened to carry.
     */
    void record(AuthEventType type, UUID companyId, UUID userId, Map<String, ?> detail) {
        store.append(companyId, userId, type, clientAddress(), Map.copyOf(detail));
    }

    /** Records a change {@code actorUserId} made to the user's access or account; the detail names them. */
    void recordBy(UUID actorUserId, AuthEventType type, UUID companyId, UUID userId, Map<String, ?> detail) {
        Map<String, Object> all = new LinkedHashMap<>(detail);
        all.put("actorUserId", actorUserId);
        store.append(companyId, userId, type, clientAddress(), all);
    }

    /** The caller's company's trail, newest first; one user's when {@code userId} is given. */
    public Page<AuthEvent> page(UUID userId, Pageable pageable) {
        return store.page(TenantContext.require(), userId, pageable);
    }

    // the address the login throttle sees too: Tomcat has already applied X-Forwarded-For from trusted proxies
    private static String clientAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr()
                : null;
    }
}
