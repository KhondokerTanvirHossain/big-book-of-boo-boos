package io.github.khondokertanvirhossain.bigbook.core.policy;

import java.util.List;
import java.util.Set;

/**
 * The project-admin resource types and the fixed rules an {@code admin: true} membership gets for them
 * (T1, BB-R-006.2). Semantics, so counted.
 *
 * <p>Two rules, and both matter:
 * <ul>
 *   <li>{@code resourceType: "*"} in a policy <b>never</b> covers these types. A policy saying "everything"
 *       does not hand out {@code Project} or {@code User}.
 *   <li>{@code membership.admin} does <b>not</b> bypass (T1) — only {@code Project.superAdmin} does. An admin
 *       gets its own policy <i>plus</i> these entries, with Medplum's hidden and readonly field lists.
 * </ul>
 */
public final class AdminTypeRules {

    /**
     * Types a wildcard never covers (T1). `Package*` and `UserSecurityRequest` are v0.2 types, listed now.
     *
     * <p>{@code AccessPolicy} is here and <b>not</b> in Medplum's own list (ruled 2026-09-24; a deliberate
     * divergence). Measured on the running stack: a non-admin member with the default `*` policy could
     * {@code POST /fhir/R4/AccessPolicy} and get 201. Authoring is not attaching — that needs
     * {@code ProjectMembership}, which this list already protects — but a member who can author the resource
     * that constrains them is one {@code ProjectMembership} bug away from choosing their own access, and T1
     * exists so that assumption is not load-bearing.
     */
    public static final Set<String> PROJECT_ADMIN_TYPES = Set.of(
            "Project", "ProjectMembership", "User", "Cron", "PackageRegistry", "PackageVersion",
            "UserSecurityRequest", "AccessPolicy");

    private AdminTypeRules() {
    }

    public static boolean isProjectAdminType(String resourceType) {
        // Set.of throws on contains(null), and a null type reaches here from HAPI's internal searches (a
        // conditional reference in a transaction has no resource name on the request). Treating null as "an
        // admin type" is the fail-closed answer: a wildcard entry then does not cover it.
        return resourceType == null || PROJECT_ADMIN_TYPES.contains(resourceType);
    }

    /**
     * What an admin membership may do with the project-admin types, as Medplum's injected policy does
     * (BB-R-006.2): read and update, with the fields that would let an admin escalate held back.
     */
    public static List<CompiledPolicy.Entry> forAdminMembership() {
        Set<Interaction> readAndUpdate = Set.of(Interaction.READ, Interaction.SEARCH, Interaction.UPDATE, Interaction.HISTORY, Interaction.VREAD);
        return List.of(
                // superAdmin/systemSecret/strictMode hidden, features/link/systemSetting readonly
                new CompiledPolicy.Entry("Project", new Criteria.Always(), readAndUpdate,
                        List.of("superAdmin", "systemSecret", "strictMode"),
                        List.of("features", "link", "systemSetting"), null),
                // passwordHash and mfaSecret are never readable; identity fields are not an admin's to change
                new CompiledPolicy.Entry("User", new Criteria.Always(), readAndUpdate,
                        List.of("passwordHash", "mfaSecret"),
                        List.of("email", "emailVerified", "mfaEnrolled", "project"), null),
                new CompiledPolicy.Entry("ProjectMembership", new Criteria.Always(), readAndUpdate,
                        List.of(), List.of("project", "user"), null),
                // AccessPolicy gets create and delete as well as readAndUpdate, unlike the three above:
                // authoring policies is the capability being moved to admins (2026-09-24), so it has to be a
                // capability they actually have. Administering policies without being able to write one is not
                // a narrower permission, it is a broken one.
                new CompiledPolicy.Entry("AccessPolicy", new Criteria.Always(),
                        Set.of(Interaction.values()), List.of(), List.of(), null));
    }
}
