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
    private final ProfileResources profiles;

    public TenantProvisioner(TenantStore store, KeycloakDirectory keycloak, IPartitionLookupSvc partitions,
            ProfileResources profiles) {
        this.store = store;
        this.keycloak = keycloak;
        this.partitions = partitions;
        this.profiles = profiles;
    }

    /** Creates the invitee's profile resource inside the project's HAPI partition. */
    public interface ProfileResources {
        /** @return the reference, e.g. {@code Practitioner/<id>}; idempotent on {@code identifier = email} */
        String ensureProfile(Project project, InviteRequest request, String email);
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
     * Invite (BB-R-005.4, ADR-007 §2(f)). Order: anchor row → Keycloak user → organisation membership and
     * required actions → profile resource in the project's partition → {@code active} → email. Every step is
     * idempotent by a stable key, so retrying the identical request resumes at the failed step and creates
     * nothing twice. Nothing is deleted on failure.
     *
     * @return the seat, and whether its invite email still needs sending
     */
    public Invited invite(Project project, InviteRequest request) {
        String email = TenantStore.normalizeEmail(request.email());
        String scope = request.effectiveScope();
        store.membershipByEmail(project.id(), email).ifPresent(existing -> {
            // T6: the same email in two scopes in one project is a conflict, not a second seat
            if (existing.active() && !scope.equals(existing.userScope())) {
                throw new TenantException(409, "%s already has a %s-scoped membership in this project."
                        .formatted(email, existing.userScope()));
            }
            if (existing.active()) {
                throw new TenantException(409, email + " already has a membership in this project.");
            }
        });
        store.insertProvisioningMembership(project.id(), email, request.effectiveAdmin(), "User", scope,
                request.accessPolicy(), request.access(), request.userConfiguration(), request.invitedBy());

        KeycloakDirectory.InvitedUser user = keycloak.ensureInvitedUser(
                email, request.firstName(), request.lastName(), request.password(), request.mfaRequired());
        keycloak.ensureOrganizationMember(keycloak.organizationId(project.id().toString()), user.id());

        Membership seat = store.membershipByEmail(project.id(), email).orElseThrow();
        String profile = profiles.ensureProfile(project, request, email);
        store.setMembershipProfile(seat.id(), profile);
        store.markMembershipActive(project.id(), email, user.id());
        return new Invited(store.membership(seat.id()).orElseThrow(), user, request.sendEmail());
    }

    /**
     * A machine identity (BB-R-005.5): Keycloak confidential client, {@code ClientApplication} profile and a
     * membership. The client id is the resource id (D4) and the secret stays readable (D5).
     */
    public ClientApplication createClient(Project project, String name, String accessPolicy) {
        UUID id = UUID.randomUUID();
        // a client has no email; the id doubles as the stable key, which keeps ADR-007's shape
        String key = id + "@clients.invalid";
        store.insertProvisioningMembership(project.id(), key, false, "ClientApplication", "project",
                accessPolicy, null, null, null);
        KeycloakDirectory.ConfidentialClient client = keycloak.ensureConfidentialClient(
                id.toString(), name, keycloak.organizationId(project.id().toString()));
        Membership seat = store.membershipByEmail(project.id(), key).orElseThrow();
        store.setMembershipProfile(seat.id(), "ClientApplication/" + id);
        // the seat is keyed by the token's `sub`, which for a client is its service-account user
        store.markMembershipActive(project.id(), key, client.serviceAccountUserId());
        return new ClientApplication(id, name, client.secret(), store.membership(seat.id()).orElseThrow());
    }

    /** Keycloak's own invite email; needs SMTP, which is issue #11. */
    public void sendInviteEmail(String userId) {
        keycloak.sendInviteEmail(userId);
    }

    /** The client's secret, readable on every admin read (D5). */
    public String clientSecret(String clientId) {
        return keycloak.clientSecret(clientId);
    }

    /** @param sendEmail the caller asked for an email; whether one can be sent is issue #11's question */
    public record Invited(Membership membership, KeycloakDirectory.InvitedUser user, boolean sendEmail) {}

    public record ClientApplication(UUID id, String name, String secret, Membership membership) {}

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
