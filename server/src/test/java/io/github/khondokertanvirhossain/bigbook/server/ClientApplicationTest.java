package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Issue #6: machine identities (BB-R-005.5, D4, D5). */
class ClientApplicationTest extends LiteStackTest {

    @Test
    void creatingAClientReturns201WithASecretThatKeepsWorking() {
        UUID project = newProject("Clients");

        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects/" + project + "/client",
                superAdminToken(), Map.of("name", "Nightly job"), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode client = created.getBody();
        assertThat(client.path("resourceType").asText()).isEqualTo("ClientApplication");
        assertThat(client.path("secret").asText()).isNotBlank();
        String clientId = client.path("id").asText();

        // D5: retrievable on every admin read, not "shown once"
        JsonNode read = call(HttpMethod.GET, "/admin/projects/" + project + "/clients/" + clientId,
                superAdminToken(), null, JsonNode.class).getBody();
        assertThat(read.path("secret").asText()).isEqualTo(client.path("secret").asText());
    }

    /** D4: the resource id *is* the OAuth client_id, so the secret can be used as-is. */
    @Test
    void theClientIdIsTheOauthClientIdAndItsTokenActsInItsProject() {
        UUID project = newProject("Machine");
        JsonNode client = call(HttpMethod.POST, "/admin/projects/" + project + "/client",
                superAdminToken(), Map.of("name", "Integration"), JsonNode.class).getBody();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", client.path("id").asText());
        form.add("client_secret", client.path("secret").asText());
        form.add("scope", "openid organization:" + project);
        ResponseEntity<JsonNode> token = call(HttpMethod.POST, "/oauth2/token", null, form, JsonNode.class,
                "Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);
        String bearer = token.getBody().path("access_token").asText();

        // it can write in its own project…
        ResponseEntity<JsonNode> written = call(HttpMethod.POST, "/fhir/R4/Patient", bearer,
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"ByMachine\"}]}", JsonNode.class);
        assertThat(written.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // …and /auth/me shows it as a ClientApplication seat, not a user (T7)
        JsonNode me = call(HttpMethod.GET, "/auth/me", bearer, null, JsonNode.class).getBody();
        assertThat(me.path("membership").path("profile").path("reference").asText())
                .isEqualTo("ClientApplication/" + client.path("id").asText());
    }

    @Test
    void aClientOfOneProjectCannotReadAnother() {
        UUID projectA = newProject("Machine A");
        UUID projectB = newProject("Machine B");
        JsonNode client = call(HttpMethod.POST, "/admin/projects/" + projectA + "/client",
                superAdminToken(), Map.of("name", "Scoped"), JsonNode.class).getBody();
        String inB = call(HttpMethod.POST, "/fhir/R4/Patient", superAdminToken(),
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"InB\"}]}", JsonNode.class,
                "X-Project", projectB.toString()).getBody().path("id").asText();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", client.path("id").asText());
        form.add("client_secret", client.path("secret").asText());
        form.add("scope", "openid organization:" + projectA);
        String bearer = call(HttpMethod.POST, "/oauth2/token", null, form, JsonNode.class,
                "Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE).getBody().path("access_token").asText();

        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + inB, bearer, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // Asking for another project's organisation scope: Keycloak *issues* a token (the scope is not an
        // error for a client that is not a member) but leaves the `organization` claim out, so Big Book has
        // no project to act in and refuses the request. Measured, issue #6 — the refusal is Big Book's, not
        // Keycloak's, which is why this asserts the FHIR call rather than the token endpoint.
        MultiValueMap<String, String> wrong = new LinkedMultiValueMap<>(form);
        wrong.set("scope", "openid organization:" + projectB);
        ResponseEntity<JsonNode> crossProject = call(HttpMethod.POST, "/oauth2/token", null, wrong, JsonNode.class,
                "Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(crossProject.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(crossProject.getBody().path("access_token").asText()).isNotBlank();
        String unbound = crossProject.getBody().path("access_token").asText();
        ResponseEntity<JsonNode> refused = call(HttpMethod.GET, "/fhir/R4/Patient/" + inB, unbound, null, JsonNode.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("issue").path(0).path("diagnostics").asText())
                .contains("not bound to a project");
    }

    private UUID newProject(String name) {
        return UUID.fromString(call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", name + "-" + UUID.randomUUID()), JsonNode.class).getBody().path("id").asText());
    }
}
