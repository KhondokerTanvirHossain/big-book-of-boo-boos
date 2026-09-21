package io.github.khondokertanvirhossain.bigbook.core.tenant;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * The Keycloak Admin REST calls provisioning needs, each idempotent by its stable key (ADR-007):
 * Keycloak answers 409 to a duplicate organisation, user or member, and that means "continue".
 */
public class KeycloakDirectory {

    private final RealmResource realm;

    public KeycloakDirectory(RealmResource realm) {
        this.realm = realm;
    }

    /** @return the organisation's Keycloak id; the alias is the project id */
    public String ensureOrganization(String alias, String name) {
        OrganizationRepresentation organization = new OrganizationRepresentation();
        organization.setAlias(alias);
        organization.setName(alias);
        organization.setDescription(name);
        try (Response response = realm.organizations().create(organization)) {
            requireCreatedOrExists(response, "organisation " + alias);
        }
        return organizationId(alias);
    }

    public String organizationId(String alias) {
        return realm.organizations().search(alias, true, 0, 1).get(0).getId();
    }

    /**
     * @param onCreated runs only when this call created the user, with nothing in between
     * @return the user's Keycloak id, the token {@code sub}
     */
    public String ensureUser(String email, String firstName, String lastName, String password, Runnable onCreated) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        // Keycloak's default user profile will not let a user without names log in
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));
        try (Response response = realm.users().create(user)) {
            if (requireCreatedOrExists(response, "user " + email)) {
                onCreated.run();
            }
        }
        return realm.users().searchByEmail(email, true).get(0).getId();
    }

    public void ensureOrganizationMember(String organizationId, String userId) {
        try (Response response = realm.organizations().get(organizationId).members().addMember(userId)) {
            requireCreatedOrExists(response, "organisation member " + userId);
        }
    }

    /**
     * The invite's Keycloak half (BB-R-005.4). Idempotent by email, which is ADR-007's stable key.
     *
     * @param scopeProject the user belongs to this project only, so it is created org-bound
     * @param requireTotp pre-provision Keycloak's TOTP required action (BB-R-004.3, `mfaRequired`)
     * @return the user's Keycloak id, and whether this call created it
     */
    public InvitedUser ensureInvitedUser(
            String email, String firstName, String lastName, String password, boolean requireTotp) {
        List<UserRepresentation> existing = realm.users().searchByEmail(email, true);
        if (!existing.isEmpty()) {
            // T6: an existing user is reused and password/mfaRequired/names are ignored, as Medplum does
            return new InvitedUser(existing.get(0).getId(), false);
        }
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        // the invitee proves the address by following the set-password link (issue #11)
        user.setEmailVerified(false);
        if (password != null && !password.isBlank()) {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(password);
            credential.setTemporary(false);
            user.setCredentials(List.of(credential));
        }
        List<String> actions = new java.util.ArrayList<>();
        if (password == null || password.isBlank()) {
            actions.add("UPDATE_PASSWORD");
        }
        if (requireTotp) {
            actions.add("CONFIGURE_TOTP");
        }
        user.setRequiredActions(actions);
        try (Response response = realm.users().create(user)) {
            requireCreatedOrExists(response, "user " + email);
        }
        return new InvitedUser(realm.users().searchByEmail(email, true).get(0).getId(), true);
    }

    /** @param created false when the user already existed, so their credentials were left alone (T6) */
    public record InvitedUser(String id, boolean created) {}

    /**
     * A machine identity: a Keycloak confidential client whose {@code clientId} is the
     * {@code ClientApplication} id (D4), so the OAuth {@code client_id} and the resource id are one value.
     *
     * @return the secret, which stays readable by project admins (D5), and the service-account user id —
     *     which is the {@code sub} of every token the client gets, so it is what the membership is keyed by
     */
    public ConfidentialClient ensureConfidentialClient(String clientId, String name, String organizationId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setName(name);
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setStandardFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        try (Response response = realm.clients().create(client)) {
            requireCreatedOrExists(response, "client " + clientId);
        }
        ClientRepresentation created = realm.clients().findByClientId(clientId).get(0);
        // the service-account user is what carries the organisation membership, so tokens get `organization`
        UserRepresentation serviceAccount = realm.clients().get(created.getId()).getServiceAccountUser();
        ensureOrganizationMember(organizationId, serviceAccount.getId());
        return new ConfidentialClient(
                realm.clients().get(created.getId()).getSecret().getValue(), serviceAccount.getId());
    }

    /** @param serviceAccountUserId the {@code sub} its tokens carry, not the client id (measured, issue #6) */
    public record ConfidentialClient(String secret, String serviceAccountUserId) {}

    /** The secret of an existing confidential client; readable on every admin read (D5). */
    public String clientSecret(String clientId) {
        List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
        return found.isEmpty() ? null : realm.clients().get(found.get(0).getId()).getSecret().getValue();
    }

    /** Sends Keycloak's own invite email: set password, and verify the address. Requires SMTP (issue #11). */
    public void sendInviteEmail(String userId) {
        realm.users().get(userId).executeActionsEmail(List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
    }

    /** Keycloak answers 204 whether or not the user already had the role. */
    public void ensureRealmRole(String userId, String role) {
        realm.users().get(userId).roles().realmLevel().add(List.of(realm.roles().get(role).toRepresentation()));
    }

    /** @return true when this call created it, false when it already existed */
    private static boolean requireCreatedOrExists(Response response, String what) {
        if (response.getStatus() == 201) {
            return true;
        }
        if (response.getStatus() == 409) {
            return false;
        }
        throw new IllegalStateException("Keycloak answered " + response.getStatus() + " creating " + what);
    }
}
