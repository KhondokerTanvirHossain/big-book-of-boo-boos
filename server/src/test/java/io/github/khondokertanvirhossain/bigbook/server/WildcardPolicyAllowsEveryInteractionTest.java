package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Every {@link Interaction} must be <b>positively</b> exercised under a {@code *} policy: the caller is
 * permitted, and the request succeeds.
 *
 * <p>This exists because of a real bug. The {@code delete} branch of the rule builder was the one verb built
 * with {@code resourcesOfType("*")} instead of the wildcard helper, so HAPI received a resource type literally
 * named {@code "*"}, matched nothing, and silently refused every delete a {@code *} policy should have
 * permitted. Every negative test still passed — a caller who should be denied was denied. Nothing asserted the
 * allow case, so nothing failed.
 *
 * <p>The guard against that returning is {@link #everyInteractionIsCoveredByThisTest()}: it enumerates
 * {@link Interaction} and fails if a new value is added without a positive case here. A test that lists the
 * interactions by hand would drift the moment the enum grew.
 */
class WildcardPolicyAllowsEveryInteractionTest extends LiteStackTest {

    static UUID project;
    static String member;

    /** Filled by each test as it proves its interaction; checked against the enum at the end. */
    private static final Set<Interaction> proven = EnumSet.noneOf(Interaction.class);

    @BeforeEach
    void oneProjectOneSeat() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "WildcardInteractions"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        member = "Wildcard-" + UUID.randomUUID() + "@bigbook.test";
        // a membership with no AccessPolicy compiles to full project access: the "*" policy, T1/BB-R-006.2
        seedSeat(project, member, seedUser(member), false);
    }

    @Test
    void createIsPermitted() {
        ResponseEntity<JsonNode> created = post("/fhir/R4/Patient", patient("CreateProbe"));

        assertSucceeded(Interaction.CREATE, created);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void readIsPermitted() {
        String id = createPatient("ReadProbe");

        ResponseEntity<JsonNode> read = get("/fhir/R4/" + id);

        assertSucceeded(Interaction.READ, read);
        assertThat(read.getBody().path("resourceType").asText()).isEqualTo("Patient");
    }

    @Test
    void vreadIsPermitted() {
        String id = createPatient("VreadProbe");

        ResponseEntity<JsonNode> vread = get("/fhir/R4/" + id + "/_history/1");

        assertSucceeded(Interaction.VREAD, vread);
        assertThat(vread.getBody().path("resourceType").asText()).isEqualTo("Patient");
    }

    @Test
    void updateIsPermitted() {
        String id = createPatient("UpdateProbe");
        Map<String, Object> updated = Map.of("resourceType", "Patient",
                "id", id.substring("Patient/".length()),
                "name", List.of(Map.of("family", "UpdatedProbe")));

        ResponseEntity<JsonNode> update = call(HttpMethod.PUT, "/fhir/R4/" + id, token(), updated, JsonNode.class);

        assertSucceeded(Interaction.UPDATE, update);
        assertThat(update.getBody().path("name").path(0).path("family").asText()).isEqualTo("UpdatedProbe");
    }

    @Test
    void searchIsPermitted() {
        createPatient("SearchProbe");

        ResponseEntity<JsonNode> search = get("/fhir/R4/Patient?family=SearchProbe");

        assertSucceeded(Interaction.SEARCH, search);
        assertThat(search.getBody().path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(search.getBody().path("entry")).as("the search must return the resource, not an empty bundle")
                .isNotEmpty();
    }

    @Test
    void historyIsPermitted() {
        String id = createPatient("HistoryProbe");

        ResponseEntity<JsonNode> history = get("/fhir/R4/" + id + "/_history");

        assertSucceeded(Interaction.HISTORY, history);
        assertThat(history.getBody().path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(history.getBody().path("entry")).isNotEmpty();
    }

    /**
     * The interaction the bug was in. A {@code *} policy permits delete, so this must be a 2xx — and before the
     * wildcard fix it was a 403, with every negative test still green.
     */
    @Test
    void deleteIsPermitted() {
        String id = createPatient("DeleteProbe");

        ResponseEntity<JsonNode> deleted = call(HttpMethod.DELETE, "/fhir/R4/" + id, token(), null, JsonNode.class);

        assertSucceeded(Interaction.DELETE, deleted);
        assertThat(get("/fhir/R4/" + id).getStatusCode())
                .as("and the resource is really gone, so the 2xx was not a no-op")
                .isEqualTo(HttpStatus.GONE);
    }

    /**
     * The guard that keeps this test honest as {@link Interaction} grows: every value must have been proven by
     * one of the cases above. Ordered last by name so JUnit's default method ordering runs it after them.
     */
    @Test
    void zzEveryInteractionIsCoveredByThisTest() {
        // re-run the cases rather than depend on test ordering: each is independent and idempotent
        createIsPermitted();
        readIsPermitted();
        vreadIsPermitted();
        updateIsPermitted();
        searchIsPermitted();
        historyIsPermitted();
        deleteIsPermitted();

        List<Interaction> uncovered = new ArrayList<>(List.of(Interaction.values()));
        uncovered.removeAll(proven);
        assertThat(uncovered)
                .as("a new Interaction needs a positive test here: the *-delete bug existed because nothing "
                        + "asserted the allow case, and a hand-written list would have missed it too")
                .isEmpty();
    }

    private void assertSucceeded(Interaction interaction, ResponseEntity<?> response) {
        HttpStatusCode status = response.getStatusCode();
        assertThat(status.is2xxSuccessful())
                .as("%s must be permitted by a * policy, got %s: %s", interaction, status, response.getBody())
                .isTrue();
        proven.add(interaction);
    }

    private String createPatient(String family) {
        ResponseEntity<JsonNode> created = post("/fhir/R4/Patient", patient(family));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return "Patient/" + created.getBody().path("id").asText();
    }

    private static Map<String, Object> patient(String family) {
        return Map.of("resourceType", "Patient", "name", List.of(Map.of("family", family)));
    }

    private ResponseEntity<JsonNode> post(String url, Object body) {
        return call(HttpMethod.POST, url, token(), body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String url) {
        return call(HttpMethod.GET, url, token(), null, JsonNode.class, "Cache-Control", "no-cache");
    }

    private String token() {
        return tokenFor(member, project);
    }
}
