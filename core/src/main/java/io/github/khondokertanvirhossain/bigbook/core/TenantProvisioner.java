package io.github.khondokertanvirhossain.bigbook.core;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-store provisioning, ADR-007: anchor on a Big Book row in {@code provisioning}, then Keycloak,
 * then HAPI, then {@code active}. Every step is idempotent by a stable key, so calling a method again
 * after a failure resumes at the failed step. Nothing is ever deleted to compensate.
 */
public class TenantProvisioner {

    /** Realm role that marks the super-admin in a token ({@code realm_access.roles}). */
    public static final String SUPER_ADMIN_ROLE = "super-admin";

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioner.class);

    private final TenantStore store;
    private final KeycloakDirectory keycloak;
    private final IPartitionLookupSvc partitions;

    public TenantProvisioner(TenantStore store, KeycloakDirectory keycloak, IPartitionLookupSvc partitions) {
        this.store = store;
        this.keycloak = keycloak;
        this.partitions = partitions;
    }

    /** The one super-admin project; idempotent, run on every start (issue #3). */
    public Project ensureSuperAdminProject(String name) {
        return provision(store.insertProvisioning(UUID.randomUUID(), name, true, "{}"));
    }

    /**
     * Create a project, or resume one. The retry key is {@code id} when the caller supplies it;
     * otherwise an unfinished create with the same name, so that an identical retry resumes instead
     * of starting a second project. Once a project is active its name keys nothing: names may repeat.
     */
    public Project createProject(UUID id, String name, String settings) {
        Project anchor = (id != null ? store.project(id) : store.provisioningProjectNamed(name))
                .orElseGet(() -> store.insertProvisioning(id != null ? id : UUID.randomUUID(), name, false, settings));
        return anchor.active() ? anchor : provision(anchor);
    }

    /**
     * An admin seat in the project for a Keycloak user with this email, created if absent. The profile
     * resource is not created here; that is the invite flow's (issue #6).
     *
     * @param onUserCreated runs only if this call created the Keycloak user, so its password is new
     */
    public void ensureAdminMembership(
            Project project, String email, String password, boolean superAdmin, Runnable onUserCreated) {
        String normalized = TenantStore.normalizeEmail(email);
        store.insertProvisioningMembership(project.id(), normalized, true);
        String userId = keycloak.ensureUser(normalized, "Super", "Admin", password, onUserCreated);
        keycloak.ensureOrganizationMember(keycloak.organizationId(project.id().toString()), userId);
        if (superAdmin) {
            keycloak.ensureRealmRole(userId, SUPER_ADMIN_ROLE);
        }
        store.markMembershipActive(project.id(), normalized, userId);
    }

    /**
     * Startup sweep (ADR-007): finish creates that were interrupted, report what still cannot finish.
     *
     * @return how many projects are still {@code provisioning} afterwards
     */
    public int reconcile(int olderThanMinutes) {
        int stragglers = 0;
        for (Project project : store.provisioningProjectsOlderThan(olderThanMinutes)) {
            try {
                provision(project);
                log.info("Reconciled project {}: now active", project.id());
            } catch (RuntimeException failure) {
                stragglers++;
                log.warn("Straggler: project {} ({}) is still provisioning: {}", project.id(), project.name(), failure.toString());
            }
        }
        long seats = store.provisioningMemberships();
        if (seats > 0) {
            // their steps belong to the invite flow (issue #6); until then they are reported, never touched
            log.warn("Straggler: {} project membership(s) still provisioning", seats);
        }
        return stragglers;
    }

    /** Steps 2–4 of ADR-007 for a project whose anchor row exists. */
    private Project provision(Project project) {
        // BB-R-005.14: alias and name are both the project id, because Keycloak holds each of them unique
        keycloak.ensureOrganization(project.id().toString(), project.name());
        ensurePartition(project);
        store.markProjectActive(project.id());
        return store.project(project.id()).orElseThrow();
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
