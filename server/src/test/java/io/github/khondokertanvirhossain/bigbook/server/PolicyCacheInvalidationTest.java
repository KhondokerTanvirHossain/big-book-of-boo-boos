package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The compiled-policy cache, tested for the thing that makes caching a policy dangerous: <b>an edit must take
 * effect on the next request</b>.
 *
 * <p>A cache keyed on the membership alone would pass every "the policy is enforced" test and still leave a
 * caller holding access that had been revoked. So the assertion here is not "the cache is fast" but "narrowing
 * a policy narrows the caller immediately", which is the property ADR-001's "cached per membership on policy
 * versions" exists to preserve.
 */
class PolicyCacheInvalidationTest extends LiteStackTest {

    @Autowired
    JdbcClient jdbc;

    static UUID project;
    static String member;
    static String policyId;
    static String author;

    @BeforeEach
    void aMembershipWithAPolicy() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "CacheInvalidation"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        member = "Cache-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, member, seedUser(member), true);

        // The policy is written and later edited by a *separate* seat with full project access. The member
        // under test must not be able to edit its own policy, and once it is narrowed to Observation it could
        // not reach /fhir/R4/AccessPolicy anyway — see the AccessPolicy-authoring question raised on #7.
        author = "CacheAuthor-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, author, seedUser(author), true);

        ResponseEntity<JsonNode> policy = call(HttpMethod.POST, "/fhir/R4/AccessPolicy", tokenFor(author, project),
                Map.of("resourceType", "AccessPolicy", "name", "All observations",
                        "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))),
                JsonNode.class);
        assertThat(policy.getStatusCode()).as(String.valueOf(policy.getBody())).isEqualTo(HttpStatus.CREATED);
        policyId = policy.getBody().path("id").asText();
        jdbc.sql("UPDATE bigbook.project_membership SET access_policy = ? WHERE project_id = ? AND email = ?")
                .params("AccessPolicy/" + policyId, project, member.toLowerCase())
                .update();
    }

    @Test
    void narrowingAPolicyTakesEffectOnTheNextRequest() {
        String token = tokenFor(member, project);
        // the policy permits status=final, so a final Observation is visible
        String observation = createObservation(token, "final");
        assertThat(read(token, observation).getStatusCode())
                .as("the baseline must pass, or the revocation below proves nothing")
                .isEqualTo(HttpStatus.OK);

        // now narrow the policy to something the resource does not satisfy. The membership row is untouched:
        // only the policy document and its version change, which is exactly the case a membership-keyed cache
        // would miss.
        ResponseEntity<JsonNode> updated = call(HttpMethod.PUT, "/fhir/R4/AccessPolicy/" + policyId, tokenFor(author, project),
                Map.of("resourceType", "AccessPolicy", "id", policyId, "name", "Cancelled only",
                        "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=cancelled"))),
                JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).as(String.valueOf(updated.getBody())).isTrue();

        assertThat(read(token, observation).getStatusCode())
                .as("the narrowed policy must apply at once: a cached compile here is retained access")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String createObservation(String token, String status) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/Observation", token,
                Map.of("resourceType", "Observation", "status", status, "code", Map.of("text", "cache probe")),
                JsonNode.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return "Observation/" + created.getBody().path("id").asText();
    }

    private ResponseEntity<JsonNode> read(String token, String id) {
        return call(HttpMethod.GET, "/fhir/R4/" + id, token, null, JsonNode.class, "Cache-Control", "no-cache");
    }
}
