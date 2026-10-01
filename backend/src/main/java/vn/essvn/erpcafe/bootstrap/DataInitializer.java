package vn.essvn.erpcafe.bootstrap;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.essvn.erpcafe.common.context.TenantContext;
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
 *
 * <p>Startup acts for no request, so the catalog sync runs as the system across every company
 * ({@link TenantContext#runAsSystem}); the first company's own rows are written inside that
 * company's tenant, the only way a tenant-owned row is ever written. Each step opens its own
 * transaction, because a session takes its tenant when it opens.
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
    private final TransactionTemplate transactions;

    public DataInitializer(PermissionRepository permissionRepository, RoleRepository roleRepository,
            UserRepository userRepository, UserBranchAccessRepository accessRepository,
            CompanyRepository companyRepository, BranchRepository branchRepository,
            PasswordEncoder passwordEncoder, SecurityProperties securityProperties,
            PlatformTransactionManager transactionManager) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean empty = asSystem(() -> {
            syncPermissions();
            return userRepository.count() == 0;
        });
        if (empty) {
            String password = bootstrapPassword();
            Company company = asSystem(this::findOrCreateCompany);
            TenantContext.runAs(company.getId(),
                    () -> transactions.executeWithoutResult(status -> seedTenant(company, password)));
        }
        syncDefaultRolePermissions(Permissions.CATALOG_VERSION, Permissions.SINCE);
    }

    /**
     * Grants each default role the permissions its {@link DefaultRole} definition lists that
     * entered the catalog after the version the role last synced to, then records
     * {@code catalogVersion} on the role unless it already has a newer one (after a rollback, the
     * next upgrade must not offer that release's permissions again). A permission is offered once,
     * when it is new: an upgraded deployment's managers and cashiers still receive what a release
     * adds, or they would 403 on every new endpoint until edited by hand (AUTH-G06), while a
     * permission an owner removed stays removed across restarts. Never revokes, and roles with
     * names outside the default set are not touched at all.
     *
     * @param catalogVersion the catalog version being synced to ({@link Permissions#CATALOG_VERSION})
     * @param sinceVersion permission name → the catalog version that introduced it ({@link Permissions#SINCE})
     */
    public void syncDefaultRolePermissions(int catalogVersion, Map<String, Integer> sinceVersion) {
        TenantContext.runAsSystem(() -> transactions.executeWithoutResult(
                status -> syncEachDefaultRole(catalogVersion, sinceVersion)));
    }

    private void syncEachDefaultRole(int catalogVersion, Map<String, Integer> sinceVersion) {
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
                boolean newSinceLastSync = sinceVersion.getOrDefault(name, 0) > role.getSyncedCatalogVersion();
                if (newSinceLastSync && !held.contains(name)) {
                    permissionRepository.findByName(name).ifPresent(role.getPermissions()::add);
                }
            }
            if (role.getPermissions().size() != before) {
                log.info("Granted {} new default permission(s) to {} role {}",
                        role.getPermissions().size() - before, role.getName(), role.getId());
            }
            role.setSyncedCatalogVersion(Math.max(role.getSyncedCatalogVersion(), catalogVersion));
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

    // the transaction opens inside the system scope, so its session takes that scope
    private <T> T asSystem(Supplier<T> work) {
        return TenantContext.runAsSystem(() -> transactions.execute(status -> work.get()));
    }

    private Company findOrCreateCompany() {
        return companyRepository.findAll().stream().findFirst()
                .orElseGet(() -> companyRepository.save(new Company("ESS Cafe", "ESS")));
    }

    private String bootstrapPassword() {
        String password = securityProperties.bootstrap().adminPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "app.security.bootstrap.admin-password is required to seed an empty database");
        }
        return password;
    }

    private void seedTenant(Company company, String password) {
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

        String email = User.normalizeEmail(securityProperties.bootstrap().adminEmail());
        User admin = new User(company.getId(), email,
                passwordEncoder.encode(password),
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
