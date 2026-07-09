package vn.essvn.erpcafe.identity.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/** Convenience accessor for the authenticated {@link AuthPrincipal}. */
@Component
public class CurrentUser {

    public Optional<AuthPrincipal> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public AuthPrincipal require() {
        return get().orElseThrow(() -> new BusinessRuleException("No authenticated user"));
    }
}
