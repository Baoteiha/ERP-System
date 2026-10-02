package vn.essvn.erpcafe.identity.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.identity.domain.AuthEventType;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.security.UserGrantCache;
import vn.essvn.erpcafe.organization.api.BranchCreated;
import vn.essvn.erpcafe.organization.api.BranchDeactivated;
import vn.essvn.erpcafe.organization.api.BranchDeleted;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: BranchAccessListener
 * Purpose: Keeps branch access grants in step with the branch lifecycle, in the transaction that changed the branch.
 */
@Component
public class BranchAccessListener {

    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;
    private final OrganizationApi organizationApi;
    private final UserGrantCache grantCache;
    private final AuthVersions authVersions;
    private final AuthEventLog events;

    BranchAccessListener(UserBranchAccessRepository accessRepository, RoleRepository roleRepository,
            OrganizationApi organizationApi, UserGrantCache grantCache, AuthVersions authVersions,
            AuthEventLog events) {
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
        this.organizationApi = organizationApi;
        this.grantCache = grantCache;
        this.authVersions = authVersions;
        this.events = events;
    }

    /**
     * Not routed through AccessPolicy: the creator just made this branch, so there is nothing
     * to be escalated. An owner gets OWNER; anyone else gets the role they hold at the branch
     * they acted from, never more. Only grants on live branches count, and a branch created
     * inactive gets no grant: one can be given once it is activated.
     */
    @EventListener
    public void onBranchCreated(BranchCreated event) {
        if (!event.active()) {
            return;
        }
        Set<UUID> liveBranchIds = organizationApi.listActiveBranches(event.companyId()).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        List<UserBranchAccess> held = accessRepository.findByUserId(event.actorUserId()).stream()
                .filter(g -> liveBranchIds.contains(g.getBranchId()))
                .toList();
        List<Role> heldRoles = roleRepository.findAllById(
                held.stream().map(UserBranchAccess::getRoleId).distinct().toList());
        Optional<Role> owner = heldRoles.stream()
                .filter(r -> DefaultRole.OWNER.roleName().equals(r.getName())).findFirst();
        Optional<UUID> roleId = owner.map(Role::getId).or(() -> BranchContext.current()
                .flatMap(actedFrom -> held.stream()
                        .filter(g -> g.getBranchId().equals(actedFrom)).findFirst())
                .map(UserBranchAccess::getRoleId));
        roleId.ifPresent(id -> {
            roleRepository.lockByIdAndCompanyId(id, event.companyId());
            accessRepository.save(new UserBranchAccess(event.actorUserId(), event.branchId(), id));
            grantCache.evictAfterCommit(event.actorUserId());
            events.record(AuthEventType.ACCESS_GRANTED, event.companyId(), event.actorUserId(),
                    Map.of("branchId", event.branchId(), "roleId", id, "reason", "BRANCH_CREATED"));
        });
    }

    @EventListener
    public void onBranchDeleted(BranchDeleted event) {
        revokeAll(event.branchId(), event.companyId(), event.actorUserId());
    }

    @EventListener
    public void onBranchDeactivated(BranchDeactivated event) {
        revokeAll(event.branchId(), event.companyId(), event.actorUserId());
    }

    // The grant cache alone would drop the branch from tokens already issued; ending those tokens
    // makes each holder's next request refresh into one that no longer names it (R26).
    private void revokeAll(UUID branchId, UUID companyId, UUID actorUserId) {
        List<UserBranchAccess> grants = accessRepository.findByBranchId(branchId);
        accessRepository.deleteAll(grants);
        grants.forEach(g -> events.recordBy(actorUserId, AuthEventType.ACCESS_REVOKED, companyId, g.getUserId(),
                Map.of("branchId", branchId, "roleId", g.getRoleId(), "reason", "BRANCH_CLOSED")));
        authVersions.bump(companyId, grants.stream().map(UserBranchAccess::getUserId).distinct().toList());
    }
}
