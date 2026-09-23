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
 * {@code AccessPolicy} is a project-admin type (T1), ruled 2026-09-24 — a <b>deliberate divergence</b>: it is
 * not in Medplum's own list.
 *
 * <p>The reason it moved: measured on the running stack, a non-admin member with the default {@code *} policy
 * could create an {@code AccessPolicy} and got 201. Authoring is not attaching — that needs
 * {@code ProjectMembership}, already an admin type — but a member who can author the resource that constrains
 * them is one {@code ProjectMembership} bug away from choosing their own access, and T1 exists so that
 * assumption is not load-bearing.
 *
 * <p>Both halves are asserted here. A rule that only refuses is half a rule: if the admin case broke, every
 * negative test below would still pass and policy administration would simply be impossible.
 */
class AccessPolicyIsAnAdminTypeTest extends LiteStackTest {

    static UUID project;
    static String member;
    static String admin;

    @BeforeEach
    void oneProjectAMemberAndAnAdmin() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "AdminTypeT1"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        // no policy on either seat, so both compile to the default "*" full project access
        member = "T1Member-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, member, seedUser(member), false);
        admin = "T1Admin-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, admin, seedUser(admin), true);
    }

    /** The escalation shape this ruling closes: a `*` policy must no longer reach AccessPolicy. */
    @Test
    void aNonAdminMemberWithAWildcardPolicyCannotAuthorAPolicy() {
        ResponseEntity<JsonNode> refused = post(member, Map.of(
                "resourceType", "AccessPolicy", "name", "self-authored",
                "resource", List.of(Map.of("resourceType", "*"))));

        assertThat(refused.getStatusCode())
                .as("a wildcard policy must not cover AccessPolicy (T1): %s", refused.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aNonAdminMemberCannotReadAPolicyEither() {
        String id = created(post(admin, Map.of(
                "resourceType", "AccessPolicy", "name", "admin authored",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final")))));

        ResponseEntity<JsonNode> refused = call(HttpMethod.GET, "/fhir/R4/AccessPolicy/" + id,
                tokenFor(member, project), null, JsonNode.class, "Cache-Control", "no-cache");

        assertThat(refused.getStatusCode())
                .as("reading the policies of a project is administration, not membership")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The other half: an admin must still be able to administer policies. The injected admin-type entry gives
     * {@code AccessPolicy} every interaction, unlike {@code Project}/{@code User}/{@code ProjectMembership}
     * which are read-and-update — administering policies without being able to write one is not a narrower
     * permission, it is a broken one.
     */
    @Test
    void anAdminCanAuthorReadAndUpdateAPolicy() {
        ResponseEntity<JsonNode> authored = post(admin, Map.of(
                "resourceType", "AccessPolicy", "name", "Final observations",
                "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))));
        assertThat(authored.getStatusCode()).as(String.valueOf(authored.getBody())).isEqualTo(HttpStatus.CREATED);
        String id = created(authored);

        ResponseEntity<JsonNode> read = call(HttpMethod.GET, "/fhir/R4/AccessPolicy/" + id, tokenFor(admin, project),
                null, JsonNode.class, "Cache-Control", "no-cache");
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> updated = call(HttpMethod.PUT, "/fhir/R4/AccessPolicy/" + id,
                tokenFor(admin, project),
                Map.of("resourceType", "AccessPolicy", "id", id, "name", "Cancelled observations",
                        "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=cancelled"))),
                JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("an admin must be able to narrow a policy, which is the whole point of the capability: %s",
                        updated.getBody())
                .isTrue();
    }

    /** A super-admin bypasses everything, including this (T1). */
    @Test
    void aSuperAdminStillReachesPolicies() {
        ResponseEntity<JsonNode> authored = call(HttpMethod.POST, "/fhir/R4/AccessPolicy", superAdminToken(),
                Map.of("resourceType", "AccessPolicy", "name", "super authored",
                        "resource", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))),
                JsonNode.class);

        assertThat(authored.getStatusCode()).as(String.valueOf(authored.getBody())).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> post(String user, Object body) {
        return call(HttpMethod.POST, "/fhir/R4/AccessPolicy", tokenFor(user, project), body, JsonNode.class);
    }

    private static String created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asText();
    }
}
