package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ADR-007's exit test for the invite (issue #6): interrupt it, then retry the identical request. */
class InviteProvisioningTest extends LiteStackTest {

    @Test
    void keycloakDownMidInviteLeavesTheRowProvisioningAndTheRetryResumesIt() {
        UUID project = newProject("Interrupted invite");
        String email = "interrupted-" + UUID.randomUUID() + "@bigbook.test";
        Map<String, Object> body = Map.of("resourceType", "Practitioner", "firstName", "Inter", "lastName", "Rupted",
                "email", email, "password", USER_PASSWORD, "sendEmail", false);
        // the realm's keys are cached from here, so the super-admin's token still validates while Keycloak is down
        assertThat(call(HttpMethod.GET, "/admin/projects", superAdminToken(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String bearer = superAdminToken();

        KEYCLOAK.getDockerClient().pauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        ResponseEntity<JsonNode> failed;
        try {
            failed = call(HttpMethod.POST, "/admin/projects/" + project + "/invite", bearer, body, JsonNode.class);
        } finally {
            KEYCLOAK.getDockerClient().unpauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        }

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        // the anchor row is there and unfinished: no Keycloak user, no profile, not active
        var seat = store.membershipByEmail(project, email).orElseThrow();
        assertThat(seat.status()).isEqualTo("provisioning");
        assertThat(seat.userId()).isNull();
        assertThat(seat.profile()).isNull();
        assertThat(keycloak.realm(TenantConfig.REALM).users().searchByEmail(email, true)).isEmpty();

        // retry the identical request
        ResponseEntity<JsonNode> retried = call(HttpMethod.POST, "/admin/projects/" + project + "/invite",
                superAdminToken(), body, JsonNode.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().path("id").asText()).as("the same seat, resumed").isEqualTo(seat.id().toString());
        assertThat(retried.getBody().path("status").asText()).isEqualTo("active");
        // exactly one of everything, and nothing was deleted
        assertThat(keycloak.realm(TenantConfig.REALM).users().searchByEmail(email, true)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM bigbook.project_membership WHERE project_id = :p AND email = :e")
                .param("p", project).param("e", email).query(Long.class).single()).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Practitioner?identifier=" + email, superAdminToken(), null,
                JsonNode.class, "X-Project", project.toString(), "Cache-Control", "no-cache")
                .getBody().path("total").asInt()).isEqualTo(1);
        // and the invitee can now act in the project
        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, project), null, JsonNode.class)
                .getBody().path("profile").path("reference").asText()).startsWith("Practitioner/");
    }

    /** BB-R-005.13: a seat that is still provisioning issues nothing. */
    @Test
    void aProvisioningSeatCannotAct() {
        UUID project = newProject("Provisioning seat");
        String email = "half-" + UUID.randomUUID() + "@bigbook.test";
        // a Keycloak user with an organisation membership, but a Big Book row still provisioning
        String userId = seedUser(email);
        new io.github.khondokertanvirhossain.bigbook.core.KeycloakDirectory(keycloak.realm(TenantConfig.REALM))
                .ensureOrganizationMember(keycloak.realm(TenantConfig.REALM).organizations()
                        .search(project.toString(), true, 0, 1).get(0).getId(), userId);
        store.insertProvisioningMembership(project, email, false);

        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient", tokenFor(email, project), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, project), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID newProject(String name) {
        return UUID.fromString(call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", name + "-" + UUID.randomUUID()), JsonNode.class).getBody().path("id").asText());
    }
}
