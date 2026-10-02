package vn.essvn.erpcafe.identity.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.identity.api.IdentityApi;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/** Implements the published {@link IdentityApi} over the security context. */
@Service
public class IdentityApiImpl implements IdentityApi {

    private final CurrentUser currentUser;

    public IdentityApiImpl(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public UUID requireUserId() {
        return currentUser.require().userId();
    }

    @Override
    public boolean hasPermission(String permission) {
        return currentUser.require().permissions().contains(permission);
    }

    @Override
    public boolean hasPermissionAt(UUID branchId, String permission) {
        return currentUser.require().permissionsAt(branchId).contains(permission);
    }

    @Override
    public boolean isOwner() {
        return currentUser.require().roles().contains(DefaultRole.OWNER.roleName());
    }
}
