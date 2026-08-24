package vn.essvn.erpcafe.identity.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * User management: creating accounts and granting per-branch role access.
 * New users belong to the acting user's company; branch grants are validated
 * against the organization module via {@link OrganizationApi}.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationApi organizationApi;
    private final CurrentUser currentUser;

    public UserService(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            RoleRepository roleRepository, PasswordEncoder passwordEncoder,
            OrganizationApi organizationApi, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.organizationApi = organizationApi;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return userRepository.findByCompanyId(currentUser.require().companyId(), pageable);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    public User create(String email, String rawPassword, String fullName) {
        UUID companyId = currentUser.require().companyId();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already exists: " + email);
        }
        User user = new User(companyId, email, passwordEncoder.encode(rawPassword), fullName);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserBranchAccess> accessOf(UUID userId) {
        return accessRepository.findByUserId(userId);
    }

    /** Grants (or updates) a user's role at a branch. */
    public UserBranchAccess grantAccess(UUID userId, UUID branchId, UUID roleId) {
        User user = get(userId);
        // The branch must belong to the user's own company. A bare existence check would
        // let a caller attach another company's branch to the user's access set, which
        // every branch-scoped query downstream then trusts (cross-tenant escalation).
        if (!organizationApi.branchExistsInCompany(branchId, user.getCompanyId())) {
            throw new ResourceNotFoundException("Branch not found or inactive: " + branchId);
        }
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Role", roleId));
        if (!role.getCompanyId().equals(user.getCompanyId())) {
            throw new BusinessRuleException("Role belongs to a different company");
        }
        return accessRepository.findByUserIdAndBranchId(userId, branchId)
                .map(existing -> {
                    existing.setRoleId(roleId);
                    return existing;
                })
                .orElseGet(() -> accessRepository.save(new UserBranchAccess(userId, branchId, roleId)));
    }

    /** Revokes a user's access at a branch. */
    public void revokeAccess(UUID userId, UUID branchId) {
        UserBranchAccess access = accessRepository.findByUserIdAndBranchId(userId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User " + userId + " has no access at branch " + branchId));
        accessRepository.delete(access);
    }
}
