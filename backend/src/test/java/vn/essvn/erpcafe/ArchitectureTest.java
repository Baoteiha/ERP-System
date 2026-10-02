package vn.essvn.erpcafe;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.identity.domain.Permissions;

/**
 * Class: ArchitectureTest
 * Purpose: Pins the module boundaries, the tenant-scope entry points and the endpoint permission rule.
 *
 * <p>{@code bootstrap..} is the composition root and {@code config..} is framework wiring: neither is a
 * business module, so the module rules do not constrain them. {@code DataInitializer} seeds identity
 * and organization rows together, so it can live in neither module without that module reaching into
 * the other's internals; it stays in {@code bootstrap} and is the one place allowed to use several
 * modules' repositories.
 */
class ArchitectureTest {

    private static final String ROOT = "vn.essvn.erpcafe";
    private static final List<String> MODULES =
            List.of("identity", "organization", "catalog", "inventory", "sales", "staff", "reporting");

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    /** Public controller methods that carry no permission check, each with the reason it is open. */
    private static final Map<String, String> OPEN_ENDPOINTS = Map.of(
            "AuthController.login", "anonymous by nature: it issues the token",
            "AuthController.refresh", "authenticated by the refresh token in the body",
            "AuthController.logout", "authenticated by the refresh token in the body",
            "AuthController.me", "any authenticated user reads their own identity",
            "UserController.changeOwnPassword", "any authenticated user changes their own password",
            "CompanyController.create", "denyAll(): companies are created by provisioning, never over HTTP");

    @Test
    void aModuleReachesAnotherOnlyThroughItsApi() {
        for (String module : MODULES) {
            DescribedPredicate<JavaClass> otherInternals = DescribedPredicate.alwaysFalse();
            for (String other : MODULES) {
                if (!other.equals(module)) {
                    otherInternals = otherInternals.or(
                            resideInAPackage(ROOT + "." + other + "..")
                                    .and(not(resideInAPackage(ROOT + "." + other + ".api.."))));
                }
            }
            noClasses().that().resideInAPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat(otherInternals)
                    .because(module + " may use another module only through its api package")
                    .check(MAIN);
        }
    }

    @Test
    void noModuleReferencesAnotherModulesRepository() {
        for (String module : MODULES) {
            noClasses().that().resideOutsideOfPackages(ROOT + "." + module + "..", ROOT + ".bootstrap..")
                    .should().dependOnClassesThat(assignableTo(Repository.class)
                            .and(resideInAPackage(ROOT + "." + module + ".persistence..")))
                    .check(MAIN);
        }
    }

    @Test
    void identitySecurityIsInternalToIdentityAndConfig() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".identity..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".identity.security..")
                .check(MAIN);
    }

    @Test
    void onlyTheJwtFilterStartsARequestScope() {
        noClasses().that().doNotHaveFullyQualifiedName(TenantContext.class.getName())
                .and().haveNameNotMatching(
                        Pattern.quote(ROOT + ".identity.security.JwtAuthenticationFilter") + "(\\$.*)?")
                .should().callMethod(TenantContext.class, "startRequest", UUID.class)
                .check(MAIN);
    }

    @Test
    void mainCodeEntersTheSystemScopeOnlyThroughSystemTransactions() {
        noClasses().that().doNotHaveFullyQualifiedName(SystemTransactions.class.getName())
                .and().doNotHaveFullyQualifiedName(TenantContext.class.getName())
                .should().callMethodWhere(target(name("runAsSystem")).and(target(owner(equivalentTo(TenantContext.class)))))
                .check(MAIN);
    }

    @Test
    void everyControllerMethodChecksAPermissionUnlessDocumentedOpen() {
        classes().that().areAnnotatedWith(RestController.class)
                .should(haveEveryPublicMethodGuardedByAKnownPermission())
                .check(MAIN);
    }

    private static ArchCondition<JavaClass> haveEveryPublicMethodGuardedByAKnownPermission() {
        return new ArchCondition<>("guard every public method with @PreAuthorize on a known permission") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (JavaMethod method : controller.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        continue;
                    }
                    String id = controller.getSimpleName() + "." + method.getName();
                    if (OPEN_ENDPOINTS.containsKey(id)) {
                        continue;
                    }
                    String problem = problemWith(method);
                    if (problem != null) {
                        events.add(SimpleConditionEvent.violated(method, id + ": " + problem));
                    }
                }
            }
        };
    }

    private static String problemWith(JavaMethod method) {
        PreAuthorize guard = method.tryGetAnnotationOfType(PreAuthorize.class).orElse(null);
        if (guard == null) {
            return "has no @PreAuthorize and is not on the open-endpoint list";
        }
        Matcher permission = HAS_AUTHORITY.matcher(guard.value());
        if (!permission.matches()) {
            return "@PreAuthorize \"" + guard.value() + "\" is not hasAuthority('<permission>')";
        }
        return Permissions.ALL.contains(permission.group(1))
                ? null
                : "@PreAuthorize names unknown permission " + permission.group(1);
    }
}
