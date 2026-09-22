package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #7's {@code AccessPolicy} route criteria. The two that matter most are the V5 fail-open forms: they are
 * refused at write time <b>and</b> grant nothing if they somehow reach the compiler, which is why
 * {@link PolicyCompilerGrantsNothingTest} exists alongside this.
 */
class AccessPolicyRouteTest extends LiteStackTest {

    static UUID project;
    static String admin;

    @BeforeEach
    void oneProjectOneAdmin() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "PolicyRoutes"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        admin = "PolicyAdmin-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, admin, seedUser(admin), true);
    }

    @Test
    void aWritablePolicyRoundTrips() {
        ResponseEntity<JsonNode> created = post(Map.of(
                "resourceType", "AccessPolicy", "name", "Final observations only",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))));

        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        // the created resource comes back, as Medplum's client expects: it reads the id off the response body
        assertThat(created.getBody()).as("a create must return the resource, not an empty 201").isNotNull();
        String id = created.getBody().path("id").asText();
        assertThat(id).isNotBlank();

        ResponseEntity<JsonNode> read = call(HttpMethod.GET, "/fhir/R4/AccessPolicy/" + id,
                tokenFor(admin, project), null, JsonNode.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().path("resourceType").asText()).isEqualTo("AccessPolicy");
    }

    /** V5a: an empty parameter value reports *supported* to HAPI and matches everything — it fails open. */
    @Test
    void anEmptyParameterValueIsRefusedAtWriteTimeAndNamesTheParameter() {
        ResponseEntity<JsonNode> refused = post(Map.of(
                "resourceType", "AccessPolicy", "name", "Fails open",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status="))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(refused.getBody().toString())
                .as("the author must be told which parameter was refused, not just that the policy is invalid")
                .contains("status");
    }

    /** V5b: :in and :not-in compare against an unexpanded ValueSet, so :not-in matches everything. */
    @Test
    void inAndNotInAreRefusedAtWriteTime() {
        for (String criteria : List.of("Observation?code:in=http://example.org/vs", "Observation?code:not-in=http://example.org/vs")) {
            ResponseEntity<JsonNode> refused = post(Map.of(
                    "resourceType", "AccessPolicy", "name", "ValueSet qualifier",
                    "resource", List.of(Map.of("resourceType", "Observation", "criteria", criteria))));
            assertThat(refused.getStatusCode()).as(criteria).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /** Chaining is outside the in-memory matcher's subset, so it cannot be enforced and is refused. */
    @Test
    void aChainedCriterionIsRefusedAtWriteTime() {
        ResponseEntity<JsonNode> refused = post(Map.of(
                "resourceType", "AccessPolicy", "name", "Chained",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?subject.name=x"))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A policy of another project is not readable, and the 404 does not confirm it exists. */
    @Test
    void aPolicyOfAnotherProjectIsNotReadable() {
        ResponseEntity<JsonNode> created = post(Map.of(
                "resourceType", "AccessPolicy", "name", "Private to this project",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))));
        String id = created.getBody().path("id").asText();

        ResponseEntity<JsonNode> other = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "OtherProject"), JsonNode.class);
        UUID otherProject = UUID.fromString(other.getBody().path("id").asText());
        String otherAdmin = "OtherAdmin-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(otherProject, otherAdmin, seedUser(otherAdmin), true);

        ResponseEntity<JsonNode> read = call(HttpMethod.GET, "/fhir/R4/AccessPolicy/" + id,
                tokenFor(otherAdmin, otherProject), null, JsonNode.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read.getBody().toString()).contains("is not known");
    }

    private ResponseEntity<JsonNode> post(Object body) {
        return call(HttpMethod.POST, "/fhir/R4/AccessPolicy", tokenFor(admin, project), body, JsonNode.class);
    }
}
