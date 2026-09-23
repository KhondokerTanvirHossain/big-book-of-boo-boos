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
 * BB-R-002 / D17: a {@code _summary} search stays summary <b>across pages</b>.
 *
 * <p>HAPI propagates {@code _count}, {@code _elements} and {@code _include} into its {@code _getpages} cursor
 * but not {@code _summary} (measured, #9 V6). The consequence is not cosmetic: page 1 is summary and tagged
 * {@code SUBSETTED}, page 2 comes back <i>full</i> and untagged, and per D44 {@code @medplum/core} caches
 * untagged resources — so it caches page 2's partial-looking-complete copies and serves them as if whole.
 *
 * <p>The assertions are on the <b>page content</b>, not the link text. A link that merely mentions
 * {@code _summary} proves nothing if the second page still returns full resources.
 */
class SearchPagingSummaryTest extends LiteStackTest {

    static UUID project;
    static String user;

    @BeforeEach
    void aProjectWithEnoughPatientsToPage() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "PagingSummary"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "Paging-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> patient = call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(user, project),
                    Map.of("resourceType", "Patient",
                            "name", List.of(Map.of("family", "Pager", "given", List.of("Number" + i))),
                            "birthDate", "1980-01-0" + (i + 1),
                            // the discriminator: Patient.communication is NOT in the summary set, while
                            // name/birthDate/telecom are. Its presence is how a full resource is told from a
                            // summary one — verified by the control below, which is HAPI's own _elements path.
                            "communication", List.of(Map.of(
                                    "language", Map.of("text", "en"), "preferred", true))),
                    JsonNode.class);
            assertThat(patient.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void theSecondPageOfASummarySearchIsStillSummaryAndStillTagged() {
        String token = tokenFor(user, project);
        ResponseEntity<JsonNode> first = call(HttpMethod.GET, "/fhir/R4/Patient?_summary=true&_count=2", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("entry")).as("the first page must have entries to page from").isNotEmpty();
        assertSummary(first.getBody(), "page 1");

        String next = nextLink(first.getBody());
        assertThat(next).as("a 5-patient search with _count=2 must offer a next page").isNotEqualTo("<no next>");

        ResponseEntity<JsonNode> second = call(HttpMethod.GET, pathOf(next), token, null, JsonNode.class,
                "Cache-Control", "no-cache");
        assertThat(second.getStatusCode()).as(String.valueOf(second.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("entry")).as("the second page must have entries").isNotEmpty();
        assertSummary(second.getBody(), "page 2");
    }

    /** _elements is HAPI's own behaviour; asserted as a control so the fix above is not credited for it. */
    @Test
    void theSecondPageOfAnElementsSearchIsStillFiltered() {
        String token = tokenFor(user, project);
        ResponseEntity<JsonNode> first = call(HttpMethod.GET, "/fhir/R4/Patient?_elements=name&_count=2", token,
                null, JsonNode.class, "Cache-Control", "no-cache");
        String next = nextLink(first.getBody());
        assertThat(next).isNotEqualTo("<no next>");

        ResponseEntity<JsonNode> second = call(HttpMethod.GET, pathOf(next), token, null, JsonNode.class,
                "Cache-Control", "no-cache");

        assertThat(second.getBody().path("entry").path(0).path("resource").has("communication"))
                .as("_elements=name must still exclude communication on page 2: %s", second.getBody())
                .isFalse();
    }

    /** Every entry on the page is summary-encoded and carries the SUBSETTED tag (D44). */
    private static void assertSummary(JsonNode bundle, String which) {
        for (JsonNode entry : bundle.path("entry")) {
            JsonNode resource = entry.path("resource");
            assertThat(resource.has("communication"))
                    .as("%s: Patient.communication is outside the summary set, so a summary page must not carry"
                            + " it: %s", which, resource)
                    .isFalse();
            assertThat(resource.path("meta").path("tag").toString())
                    .as("%s: a partial resource must be tagged SUBSETTED or @medplum/core will cache it (D44)",
                            which)
                    .contains("SUBSETTED");
        }
    }

    private static String nextLink(JsonNode bundle) {
        for (JsonNode link : bundle.path("link")) {
            if ("next".equals(link.path("relation").asText())) {
                return link.path("url").asText();
            }
        }
        return "<no next>";
    }

    /** The test client addresses the server by path; the links carry the configured public base URL. */
    private static String pathOf(String url) {
        int fhir = url.indexOf("/fhir/R4");
        return fhir < 0 ? url : url.substring(fhir);
    }
}
