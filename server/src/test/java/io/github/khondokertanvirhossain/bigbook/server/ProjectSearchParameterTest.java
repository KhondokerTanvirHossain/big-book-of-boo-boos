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
 * BB-R-002.6 / T26: {@code _project=<id>} and {@code _compartment=Project/<id>}.
 *
 * <p>Already implemented by {@link PartitionInterceptor} in issue #4, which routes both parameters through the
 * same rule as the {@code X-Project} header: a project is a HAPI partition here, so these mean "act on this
 * project" rather than "filter by it". Naming your own project is a no-op; naming another is refused with
 * <b>403</b> — the {@code X-Project} rule, where only a super-admin may select another project.
 *
 * <p>Written for #9 because BB-R-002.6 lists these under search and nothing asserted them end to end: #4 built
 * the mechanism, and this pins the behaviour a client actually sees. A refusal rather than an empty page also
 * matters for its own reason — an empty page would make the parameter a probe for whether another project holds
 * matching resources.
 */
class ProjectSearchParameterTest extends LiteStackTest {

    static UUID projectA;
    static UUID projectB;
    static String user;

    @BeforeEach
    void twoProjects() {
        if (projectA != null) {
            return;
        }
        String superAdmin = superAdminToken();
        projectA = newProject(superAdmin, "ProjectParamA");
        projectB = newProject(superAdmin, "ProjectParamB");
        user = "ProjectParam-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(projectA, user, seedUser(user), true);
        call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(user, projectA),
                Map.of("resourceType", "Patient", "name", List.of(Map.of("family", "ProjectParam"))),
                JsonNode.class);
    }

    @Test
    void namingTheCallersOwnProjectIsANoOp() {
        ResponseEntity<JsonNode> plain = search("");
        ResponseEntity<JsonNode> withProject = search("&_project=" + projectA);
        ResponseEntity<JsonNode> withCompartment = search("&_compartment=Project/" + projectA);

        assertThat(withProject.getStatusCode()).as(String.valueOf(withProject.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(withCompartment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withProject.getBody().path("entry").size())
                .as("_project naming my own project must not change the result")
                .isEqualTo(plain.getBody().path("entry").size());
        assertThat(withCompartment.getBody().path("entry").size())
                .isEqualTo(plain.getBody().path("entry").size());
        assertThat(plain.getBody().path("entry")).as("the control: the search must return something").isNotEmpty();
    }

    @Test
    void namingAnotherProjectIsRefusedRatherThanReturningEmpty() {
        ResponseEntity<JsonNode> other = search("&_project=" + projectB);

        assertThat(other.getStatusCode())
                .as("an empty page would make this a probe for whether another project exists: %s", other.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(other.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
    }

    @Test
    void namingAnotherProjectViaCompartmentIsAlsoRefused() {
        assertThat(search("&_compartment=Project/" + projectB).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A value that is not a project id is refused, not silently ignored. */
    @Test
    void aMalformedValueIsRefused() {
        ResponseEntity<JsonNode> malformed = search("&_project=not-a-uuid");

        assertThat(malformed.getStatusCode().is2xxSuccessful())
                .as("a _project that names no real project must not quietly succeed: %s", malformed.getBody())
                .isFalse();
    }

    /**
     * {@code _compartment} without the {@code Project/} prefix is <b>silently ignored</b>: #4's rule skips it
     * (no prefix to strip) and HAPI tolerates the leftover parameter, so the search runs unfiltered.
     *
     * <p>Pinned as measured behaviour rather than asserted as desirable. It is safe — the caller is confined to
     * their own partition regardless, so an ignored {@code _compartment} cannot widen anything — but a client
     * that mis-spells the value gets a normal result rather than an error, which is worth knowing. BB-R-002.6
     * only defines the {@code Project/<id>} form; tightening this would be a new decision, not a bug fix.
     */
    @Test
    void aBareIdInCompartmentIsSilentlyIgnored() {
        ResponseEntity<JsonNode> wrongShape = search("&_compartment=" + projectA);

        assertThat(wrongShape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wrongShape.getBody().path("entry"))
                .as("it is ignored, not treated as a filter that matches nothing")
                .isNotEmpty();
    }

    /** The parameter must be stripped, or HAPI rejects the whole search with HAPI-1206. */
    @Test
    void theParameterDoesNotLeakIntoHapisSearch() {
        ResponseEntity<JsonNode> response = search("&_project=" + projectA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString())
                .as("an unstripped _project would come back as HAPI-1206 unknown search parameter")
                .doesNotContain("HAPI-1206");
    }

    private ResponseEntity<JsonNode> search(String extra) {
        return call(HttpMethod.GET, "/fhir/R4/Patient?family=ProjectParam" + extra, tokenFor(user, projectA), null,
                JsonNode.class, "Cache-Control", "no-cache");
    }

    private UUID newProject(String superAdmin, String name) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdmin,
                Map.of("name", name), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(created.getBody().path("id").asText());
    }
}
