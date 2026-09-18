package io.github.khondokertanvirhossain.bigbook.core;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
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
