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
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/**
 * Class: AuthEventLog
 * Purpose: Records authentication events on the audit trail, with client address and acting user, and reads it back.
 */
@Component
public class AuthEventLog {

    private final AuthEventStore store;
    private final CurrentUser currentUser;

    AuthEventLog(AuthEventStore store, CurrentUser currentUser) {
        this.store = store;
        this.currentUser = currentUser;
    }

    /**
     * Records an event about {@code userId} of {@code companyId} (both null for a sign-in naming no account),
     * inside the caller's transaction. When someone else is signed in, the detail names them as the actor.
     */
    void record(AuthEventType type, UUID companyId, UUID userId, Map<String, ?> detail) {
        Map<String, Object> all = new LinkedHashMap<>(detail);
        currentUser.get().map(AuthPrincipal::userId)
                .filter(actor -> !actor.equals(userId))
                .ifPresent(actor -> all.put("actorUserId", actor));
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
