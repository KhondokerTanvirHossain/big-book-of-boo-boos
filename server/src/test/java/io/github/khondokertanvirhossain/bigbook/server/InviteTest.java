package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Issue #6: the invite route, its idempotency, and what it gives the rest of the system. */
class InviteTest extends LiteStackTest {

    @Test
    void anInviteCreatesAUserAProfileAndASeatAndReturns200() {
        UUID project = newProject("Invite");
        String email = "Invited.Person-" + UUID.randomUUID() + "@bigbook.test";

        ResponseEntity<JsonNode> invited = invite(project, Map.of(
                "resourceType", "Practitioner", "firstName", "Invited", "lastName", "Person",
                "email", email, "password", USER_PASSWORD, "sendEmail", false));

        // 200, not 201: Medplum's shape for this route (BB-R-005.4)
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode membership = invited.getBody();
        assertThat(membership.path("resourceType").asText()).isEqualTo("ProjectMembership");
        assertThat(membership.path("status").asText()).isEqualTo("active");
        // BB-R-005.15: stored lower-cased, whatever case the invite used
        assertThat(membership.path("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(membership.path("profile").path("reference").asText()).startsWith("Practitioner/");
        assertThat(membership.path("user").path("reference").asText()).isNotBlank();

        // the profile resource really exists, in that project's partition
        String profile = membership.path("profile").path("reference").asText();
        ResponseEntity<JsonNode> read = call(HttpMethod.GET, "/fhir/R4/" + profile, superAdminToken(), null,
                JsonNode.class, "X-Project", project.toString());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().path("name").path(0).path("family").asText()).isEqualTo("Person");
        assertThat(read.getBody().path("identifier").path(0).path("value").asText()).isEqualTo(email.toLowerCase());
    }

    /** The criterion moved from #5: /auth/me can only answer this once an invite has run. */
    @Test
    void authMeReturnsANonNullProfileForAnInvitedUser() {
        UUID project = newProject("AuthMe");
        String email = "profile-" + UUID.randomUUID() + "@bigbook.test";
        JsonNode membership = invite(project, Map.of("resourceType", "Practitioner", "firstName", "Pro",
                "lastName", "File", "email", email, "password", USER_PASSWORD, "sendEmail", false)).getBody();

        JsonNode me = call(HttpMethod.GET, "/auth/me", tokenFor(email, project), null, JsonNode.class).getBody();

        assertThat(me.path("profile").isNull()).as("#5 shipped this null; the invite is what fills it").isFalse();
        assertThat(me.path("profile").path("reference").asText())
                .isEqualTo(membership.path("profile").path("reference").asText())
                .startsWith("Practitioner/");
        assertThat(me.path("membership").path("profile").path("reference").asText()).startsWith("Practitioner/");
    }

    @Test
    void invitingTheSameEmailTwiceIntoOneProjectIs409WithNoSecondSeat() {
        UUID project = newProject("Twice");
        String email = "twice-" + UUID.randomUUID() + "@bigbook.test";
        Map<String, Object> body = Map.of("resourceType", "Practitioner", "firstName", "T", "lastName", "Wice",
                "email", email, "password", USER_PASSWORD, "sendEmail", false);

        assertThat(invite(project, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = invite(project, body);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(members(project).stream().filter(m -> m.path("email").asText().equals(email.toLowerCase())))
                .hasSize(1);
    }

    /** BB-R-005's exit test, through the invite route this time (moved from #4). */
    @Test
    void theSameEmailInvitedToTwoProjectsIsIsolatedByProject() {
        UUID projectA = newProject("Clinic A");
        UUID projectB = newProject("Clinic B");
        String email = "both-" + UUID.randomUUID() + "@bigbook.test";
        invite(projectA, Map.of("resourceType", "Practitioner", "firstName", "Both", "lastName", "Ways",
                "email", email, "password", USER_PASSWORD, "sendEmail", false, "membership", Map.of("admin", true)));
        invite(projectB, Map.of("resourceType", "Practitioner", "firstName", "Both", "lastName", "Ways",
                "email", email, "password", USER_PASSWORD, "sendEmail", false));

        String inA = call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(email, projectA),
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"OnlyInA\"}]}", JsonNode.class)
                .getBody().path("id").asText();

        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + inA, tokenFor(email, projectA), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + inA, tokenFor(email, projectB), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // admin in A, plain member in B: one Keycloak user, two seats, different privileges
        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, projectA), null, JsonNode.class)
                .getBody().path("membership").path("admin").asBoolean()).isTrue();
        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, projectB), null, JsonNode.class)
                .getBody().path("membership").path("admin").asBoolean()).isFalse();
        assertThat(keycloak.realm(TenantConfig.REALM).users().searchByEmail(email.toLowerCase(), true)).hasSize(1);
    }

    @Test
    void promotingAMemberToAdminIsVisibleInAuthMe() {
        UUID project = newProject("Promote");
        String email = "promote-" + UUID.randomUUID() + "@bigbook.test";
        JsonNode membership = invite(project, Map.of("resourceType", "Practitioner", "firstName", "P", "lastName", "Romote",
                "email", email, "password", USER_PASSWORD, "sendEmail", false)).getBody();

        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, project), null, JsonNode.class)
                .getBody().path("membership").path("admin").asBoolean()).isFalse();
        call(HttpMethod.PUT, "/admin/projects/" + project + "/members/" + membership.path("id").asText(),
                superAdminToken(), Map.of("admin", true), JsonNode.class);

        assertThat(call(HttpMethod.GET, "/auth/me", tokenFor(email, project), null, JsonNode.class)
                .getBody().path("membership").path("admin").asBoolean()).isTrue();
    }

    @Test
    void anExistingUsersCredentialsAndNamesAreLeftAlone() {
        UUID projectA = newProject("Reuse A");
        UUID projectB = newProject("Reuse B");
        String email = "reuse-" + UUID.randomUUID() + "@bigbook.test";
        invite(projectA, Map.of("resourceType", "Practitioner", "firstName", "First", "lastName", "Name",
                "email", email, "password", USER_PASSWORD, "sendEmail", false));

        // T6: a second invite reuses the user and ignores password/names
        invite(projectB, Map.of("resourceType", "Practitioner", "firstName", "Ignored", "lastName", "Ignored",
                "email", email, "password", "a-different-password", "sendEmail", false));

        UserRepresentation user = keycloak.realm(TenantConfig.REALM).users().searchByEmail(email.toLowerCase(), true).get(0);
        assertThat(user.getFirstName()).isEqualTo("First");
        // the original password still works, which is what "credentials ignored" means
        assertThat(tokenFor(email, projectB)).isNotBlank();
    }

    @Test
    void aPatientInviteDefaultsToProjectScopeAndAnExternalIdForcesIt() {
        UUID project = newProject("Scopes");
        String patientEmail = "patient-" + UUID.randomUUID() + "@bigbook.test";
        String externalEmail = "external-" + UUID.randomUUID() + "@bigbook.test";

        invite(project, Map.of("resourceType", "Patient", "firstName", "Pat", "lastName", "Ient",
                "email", patientEmail, "password", USER_PASSWORD, "sendEmail", false));
        JsonNode external = invite(project, Map.of("resourceType", "Practitioner", "firstName", "Ex", "lastName", "Ternal",
                "email", externalEmail, "externalId", "emr-12345", "password", USER_PASSWORD, "sendEmail", false)).getBody();

        assertThat(scopeOf(project, patientEmail)).as("Patient defaults to project scope (BB-R-005.2)").isEqualTo("project");
        assertThat(scopeOf(project, externalEmail)).as("an externalId forces project scope (T6)").isEqualTo("project");
        // v0.1 keeps the external id on the profile's identifier (T8)
        String profile = external.path("profile").path("reference").asText();
        JsonNode resource = call(HttpMethod.GET, "/fhir/R4/" + profile, superAdminToken(), null, JsonNode.class,
                "X-Project", project.toString()).getBody();
        assertThat(resource.path("identifier").toString()).contains("emr-12345").contains(HapiProfileResources.EXTERNAL_ID_SYSTEM);
    }

    @Test
    void sendEmailTrueWithoutSmtpSucceedsWithAWarning() {
        UUID project = newProject("NoSmtp");
        String email = "nosmtp-" + UUID.randomUUID() + "@bigbook.test";

        ResponseEntity<JsonNode> invited = invite(project, Map.of("resourceType", "Practitioner",
                "firstName", "No", "lastName", "Smtp", "email", email, "sendEmail", true));

        // issue #11 wires SMTP; until then the seat is complete and the email is reported, not fatal
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invited.getBody().path("status").asText()).isEqualTo("active");
        assertThat(invited.getBody().path("issue").path(0).path("severity").asText()).isEqualTo("warning");
        assertThat(invited.getBody().path("issue").path(0).path("diagnostics").asText()).contains("issue #11");
    }

    @Test
    void onlyAdminsMayInvite() {
        UUID project = newProject("Perms");
        String member = "plain-" + UUID.randomUUID() + "@bigbook.test";
        invite(project, Map.of("resourceType", "Practitioner", "firstName", "Plain", "lastName", "Member",
                "email", member, "password", USER_PASSWORD, "sendEmail", false));

        ResponseEntity<JsonNode> refused = call(HttpMethod.POST, "/admin/projects/" + project + "/invite",
                tokenFor(member, project), Map.of("resourceType", "Practitioner", "email", "x@bigbook.test"), JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID newProject(String name) {
        return UUID.fromString(call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", name + "-" + UUID.randomUUID()), JsonNode.class).getBody().path("id").asText());
    }

    private ResponseEntity<JsonNode> invite(UUID project, Map<String, Object> body) {
        return call(HttpMethod.POST, "/admin/projects/" + project + "/invite", superAdminToken(), body, JsonNode.class);
    }

    private List<JsonNode> members(UUID project) {
        JsonNode all = call(HttpMethod.GET, "/admin/projects/" + project + "/members", superAdminToken(), null, JsonNode.class).getBody();
        List<JsonNode> list = new java.util.ArrayList<>();
        all.forEach(list::add);
        return list;
    }

    private String scopeOf(UUID project, String email) {
        return jdbc.sql("SELECT user_scope FROM bigbook.project_membership WHERE project_id = :p AND email = :e")
                .param("p", project).param("e", email.toLowerCase()).query(String.class).single();
    }
}
