package io.github.khondokertanvirhossain.bigbook.core;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cross-store provisioning, ADR-007: anchor on a Big Book row in {@code provisioning}, then Keycloak,
 * then HAPI, then {@code active}. Every step is idempotent by a stable key, so calling a method again
 * after a failure resumes at the failed step. Nothing is ever deleted to compensate.
 */
public class TenantProvisioner {

    /** Realm role that marks the super-admin in a token ({@code realm_access.roles}). */
    public static final String SUPER_ADMIN_ROLE = "super-admin";

    public record Project(UUID id, int partitionId, String name, String status) {}

    private final JdbcClient jdbc;
    private final KeycloakDirectory keycloak;
    private final IPartitionLookupSvc partitions;

    public TenantProvisioner(JdbcClient jdbc, KeycloakDirectory keycloak, IPartitionLookupSvc partitions) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.partitions = partitions;
    }

    /** The one super-admin project: row, Keycloak organisation (alias = id), HAPI partition (name = id). */
    public Project ensureSuperAdminProject(String name) {
        jdbc.sql("""
                INSERT INTO bigbook.project (id, name, super_admin, status)
                VALUES (:id, :name, true, 'provisioning')
                ON CONFLICT (super_admin) WHERE super_admin DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("name", name)
                .update();
        Project project = jdbc.sql("SELECT id, partition_id, name, status FROM bigbook.project WHERE super_admin")
                .query(Project.class)
                .single();

        keycloak.ensureOrganization(project.id().toString(), project.name());
        ensurePartition(project);
        jdbc.sql("UPDATE bigbook.project SET status = 'active' WHERE id = :id")
                .param("id", project.id())
                .update();
        return project;
    }

    /**
     * An admin seat in the project for a Keycloak user with this email, created if absent. The profile
     * resource is not created here: it belongs in the project's partition, and partitions enter the
     * request path with issue #4.
     *
     * @param onUserCreated runs only if this call created the Keycloak user, so its password is new
     */
    public void ensureAdminMembership(
            Project project, String email, String password, boolean superAdmin, Runnable onUserCreated) {
        jdbc.sql("""
                INSERT INTO bigbook.project_membership (id, project_id, email, admin, status)
                VALUES (:id, :project, :email, true, 'provisioning')
                ON CONFLICT (project_id, email) DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("project", project.id())
                .param("email", email)
                .update();

        String userId = keycloak.ensureUser(email, "Super", "Admin", password, onUserCreated);
        keycloak.ensureOrganizationMember(keycloak.organizationId(project.id().toString()), userId);
        if (superAdmin) {
            keycloak.ensureRealmRole(userId, SUPER_ADMIN_ROLE);
        }
        jdbc.sql("""
                UPDATE bigbook.project_membership SET user_id = :user, status = 'active'
                WHERE project_id = :project AND email = :email""")
                .param("user", userId)
                .param("project", project.id())
                .param("email", email)
                .update();
    }

    /** HAPI refuses a duplicate partition name (HAPI-1309) rather than ignoring it, so look up, then create. */
    private void ensurePartition(Project project) {
        try {
            partitions.getPartitionByName(project.id().toString());
        } catch (ResourceNotFoundException absent) {
            PartitionEntity partition = new PartitionEntity();
            partition.setId(project.partitionId());
            partition.setName(project.id().toString());
            partition.setDescription(project.name());
            partitions.createPartition(partition, new SystemRequestDetails());
        }
    }
}
