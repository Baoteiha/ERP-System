package vn.essvn.erpcafe.identity.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.organization.api.BranchCreated;

/**
 * Class: BranchAccessListener
 * Purpose: Gives a branch's creator access to it, in the transaction that created the branch.
 */
@Component
public class BranchAccessListener {

    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;

    public BranchAccessListener(UserBranchAccessRepository accessRepository, RoleRepository roleRepository) {
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * Not routed through AccessPolicy: the creator just made this branch, so there is nothing
     * to be escalated. An owner gets OWNER; anyone else gets the role they hold at the branch
     * they acted from, never more.
     */
    @EventListener
    public void onBranchCreated(BranchCreated event) {
        List<UserBranchAccess> held = accessRepository.findByUserId(event.actorUserId());
        List<Role> heldRoles = roleRepository.findAllById(
                held.stream().map(UserBranchAccess::getRoleId).distinct().toList());
        Optional<Role> owner = heldRoles.stream()
                .filter(r -> DefaultRole.OWNER.roleName().equals(r.getName())).findFirst();
        Optional<UUID> roleId = owner.map(Role::getId).or(() -> BranchContext.current()
                .flatMap(actedFrom -> held.stream()
                        .filter(g -> g.getBranchId().equals(actedFrom)).findFirst())
                .map(UserBranchAccess::getRoleId));
        roleId.ifPresent(id -> accessRepository.save(
                new UserBranchAccess(event.actorUserId(), event.branchId(), id)));
    }
}
