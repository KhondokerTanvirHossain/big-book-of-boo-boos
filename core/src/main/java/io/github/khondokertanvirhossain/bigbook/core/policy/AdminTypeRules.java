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

    /** Types a wildcard never covers (T1). `Package*` and `UserSecurityRequest` are v0.2 types, listed now. */
    public static final Set<String> PROJECT_ADMIN_TYPES = Set.of(
            "Project", "ProjectMembership", "User", "Cron", "PackageRegistry", "PackageVersion", "UserSecurityRequest");

    private AdminTypeRules() {
    }

    public static boolean isProjectAdminType(String resourceType) {
        return PROJECT_ADMIN_TYPES.contains(resourceType);
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
                        List.of(), List.of("project", "user"), null));
    }
}
