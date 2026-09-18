package io.github.khondokertanvirhossain.bigbook.core;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code bigbook.project} and {@code bigbook.project_membership} rows. Plain SQL: HAPI owns the JPA unit. */
public class TenantStore {

    private static final String PROJECT = "SELECT id, partition_id, name, super_admin, status, settings::text AS settings FROM bigbook.project";
    private static final String MEMBERSHIP = """
            SELECT id, project_id, email, user_id, profile, admin, status, access_policy,
                   access::text AS access, user_configuration, invited_by
            FROM bigbook.project_membership""";

    private final JdbcClient jdbc;

    public TenantStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** BB-R-005.15: every email written or looked up goes through here. */
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public Optional<Project> project(UUID id) {
        return jdbc.sql(PROJECT + " WHERE id = :id").param("id", id).query(Project.class).optional();
    }

    public Optional<Project> superAdminProject() {
        return jdbc.sql(PROJECT + " WHERE super_admin").query(Project.class).optional();
    }

    public List<Project> projects() {
        return jdbc.sql(PROJECT + " ORDER BY created_at, id").query(Project.class).list();
    }

    /** A create that has not finished; an identical retry resumes it instead of starting another (ADR-007). */
    public Optional<Project> provisioningProjectNamed(String name) {
        return jdbc.sql(PROJECT + " WHERE status = 'provisioning' AND NOT super_admin AND name = :name ORDER BY created_at LIMIT 1")
                .param("name", name).query(Project.class).optional();
    }

    public List<Project> provisioningProjectsOlderThan(int minutes) {
        return jdbc.sql(PROJECT + " WHERE status = 'provisioning' AND created_at < now() - make_interval(mins => :minutes)")
                .param("minutes", minutes).query(Project.class).list();
    }

    /** Tx 1 of ADR-007: the anchor row. */
    public Project insertProvisioning(UUID id, String name, boolean superAdmin, String settings) {
        jdbc.sql("""
                INSERT INTO bigbook.project (id, name, super_admin, status, settings)
                VALUES (:id, :name, :superAdmin, 'provisioning', :settings::jsonb)
                ON CONFLICT DO NOTHING""")
                .param("id", id).param("name", name).param("superAdmin", superAdmin).param("settings", settings)
                .update();
        return superAdmin ? superAdminProject().orElseThrow() : project(id).orElseThrow();
    }

    public void markProjectActive(UUID id) {
        jdbc.sql("UPDATE bigbook.project SET status = 'active' WHERE id = :id").param("id", id).update();
    }

    public void updateProject(UUID id, String name, String settings) {
        jdbc.sql("UPDATE bigbook.project SET name = :name, settings = :settings::jsonb WHERE id = :id")
                .param("id", id).param("name", name).param("settings", settings).update();
    }

    public Optional<Membership> membership(UUID projectId, String userId) {
        return jdbc.sql(MEMBERSHIP + " WHERE project_id = :project AND user_id = :user")
                .param("project", projectId).param("user", userId).query(Membership.class).optional();
    }

    public Optional<Membership> membership(UUID id) {
        return jdbc.sql(MEMBERSHIP + " WHERE id = :id").param("id", id).query(Membership.class).optional();
    }

    /** @param profileType FHIR type of the profile, e.g. {@code Practitioner}; null for all */
    public List<Membership> memberships(UUID projectId, String profileType) {
        return jdbc.sql(MEMBERSHIP + " WHERE project_id = :project AND (:type::text IS NULL OR profile LIKE :type || '/%') ORDER BY created_at, id")
                .param("project", projectId).param("type", profileType).query(Membership.class).list();
    }

    public long provisioningMemberships() {
        return jdbc.sql("SELECT count(*) FROM bigbook.project_membership WHERE status = 'provisioning'").query(Long.class).single();
    }

    /** Tx 1 of ADR-007 for a seat; the stable key is (project, email). */
    public void insertProvisioningMembership(UUID projectId, String email, boolean admin) {
        jdbc.sql("""
                INSERT INTO bigbook.project_membership (id, project_id, email, admin, status)
                VALUES (:id, :project, :email, :admin, 'provisioning')
                ON CONFLICT (project_id, email) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("project", projectId)
                .param("email", normalizeEmail(email)).param("admin", admin)
                .update();
    }

    public void markMembershipActive(UUID projectId, String email, String userId) {
        jdbc.sql("""
                UPDATE bigbook.project_membership SET user_id = :user, status = 'active'
                WHERE project_id = :project AND email = :email""")
                .param("user", userId).param("project", projectId).param("email", normalizeEmail(email))
                .update();
    }

    public void updateMembership(UUID id, boolean admin, String accessPolicy, String access, String userConfiguration) {
        jdbc.sql("""
                UPDATE bigbook.project_membership
                SET admin = :admin, access_policy = :policy, access = :access::jsonb, user_configuration = :configuration
                WHERE id = :id""")
                .param("id", id).param("admin", admin).param("policy", accessPolicy)
                .param("access", access).param("configuration", userConfiguration)
                .update();
    }
}
