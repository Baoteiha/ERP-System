package vn.essvn.erpcafe.bootstrap;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;

/**
 * Composition-root bootstrap. Idempotently syncs the permission catalog on every
 * startup, and — only when there are no users yet — seeds a company, branch,
 * the default roles, and a bootstrap admin so the system is immediately usable.
 *
 * <p>As the app's wiring root, this class is permitted to touch multiple modules'
 * repositories directly (the one sanctioned exception to module boundaries).
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    public DataInitializer(PermissionRepository permissionRepository, RoleRepository roleRepository,
            UserRepository userRepository, UserBranchAccessRepository accessRepository,
            CompanyRepository companyRepository, BranchRepository branchRepository,
            PasswordEncoder passwordEncoder, SecurityProperties securityProperties) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        syncPermissions();
        if (userRepository.count() == 0) {
            seedTenant();
        }
        syncDefaultRolePermissions();
    }

    /**
     * Reconciles every default role against its {@link DefaultRole} definition, add-only:
     * as later phases add permissions to the catalog, existing companies' default roles
     * are granted the new ones on startup — not only OWNER, or an upgraded deployment's
     * managers and cashiers would 403 on every new endpoint until edited by hand
     * (AUTH-G06). Never revokes: grants an admin added beyond the definition survive,
     * and roles with names outside the default set are not touched at all.
     */
    private void syncDefaultRolePermissions() {
        for (Role role : roleRepository.findAll()) {
            DefaultRole def = DefaultRole.all().stream()
                    .filter(d -> d.roleName().equals(role.getName()))
                    .findFirst().orElse(null);
            if (def == null) {
                continue; // custom role: its permission set is the admin's, not ours
            }
            Set<String> held = new HashSet<>();
            role.getPermissions().forEach(p -> held.add(p.getName()));
            int before = role.getPermissions().size();
            for (String name : def.permissions()) {
                if (!held.contains(name)) {
                    permissionRepository.findByName(name).ifPresent(role.getPermissions()::add);
                }
            }
            if (role.getPermissions().size() != before) {
                log.info("Granted {} missing default permission(s) to {} role {}",
                        role.getPermissions().size() - before, role.getName(), role.getId());
            }
        }
    }

    private void syncPermissions() {
        Permissions.CATALOG.forEach((name, description) -> {
            if (!permissionRepository.existsByName(name)) {
                permissionRepository.save(new Permission(name, description));
                log.info("Seeded permission {}", name);
            }
        });
    }

    private void seedTenant() {
        Company company = companyRepository.findAll().stream().findFirst()
                .orElseGet(() -> companyRepository.save(new Company("ESS Cafe", "ESS")));

        Branch branch = branchRepository.findAll().stream()
                .filter(b -> b.getCompanyId().equals(company.getId()))
                .findFirst()
                .orElseGet(() -> branchRepository.save(new Branch(company.getId(), "Headquarters", "HQ")));

        for (DefaultRole def : DefaultRole.all()) {
            roleRepository.findByCompanyIdAndName(company.getId(), def.roleName())
                    .orElseGet(() -> createRole(company.getId(), def));
        }

        Role ownerRole = roleRepository.findByCompanyIdAndName(company.getId(), DefaultRole.OWNER.roleName())
                .orElseThrow();

        String email = securityProperties.bootstrap().adminEmail();
        User admin = new User(company.getId(), email,
                passwordEncoder.encode(securityProperties.bootstrap().adminPassword()),
                "Bootstrap Admin");
        admin = userRepository.save(admin);
        accessRepository.save(new UserBranchAccess(admin.getId(), branch.getId(), ownerRole.getId()));

        log.info("Seeded bootstrap admin '{}' as OWNER at branch {} (company {})",
                email, branch.getId(), company.getId());
    }

    private Role createRole(java.util.UUID companyId, DefaultRole def) {
        Role role = new Role(companyId, def.roleName(), def.description());
        Set<Permission> permissions = new HashSet<>();
        for (String permName : def.permissions()) {
            permissionRepository.findByName(permName).ifPresent(permissions::add);
        }
        role.setPermissions(permissions);
        return roleRepository.save(role);
    }
}
