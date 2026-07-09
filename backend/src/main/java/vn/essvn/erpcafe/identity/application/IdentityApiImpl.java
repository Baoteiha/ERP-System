package vn.essvn.erpcafe.identity.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/** Implements the published {@link IdentityApi} over the security context. */
@Service
public class IdentityApiImpl implements IdentityApi {

    private final CurrentUser currentUser;

    public IdentityApiImpl(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public Optional<UUID> currentUserId() {
        return currentUser.get().map(AuthPrincipal::userId);
    }

    @Override
    public Optional<UUID> currentCompanyId() {
        return currentUser.get().map(AuthPrincipal::companyId);
    }
}
