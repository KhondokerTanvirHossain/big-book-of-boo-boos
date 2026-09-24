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
 * Regression from #34: <b>following a paging link is refused</b> for any caller with a {@code *} policy.
 *
 * <p>#34 added explicit {@code deny} rules ahead of the wildcard allow, because HAPI's {@code allResources()}
 * would otherwise hand out the project-admin types (T1). A {@code _getpages} cursor request carries <i>no</i>
 * resource type in its path, and HAPI's {@code resourcesOfType} deny <b>matches</b> such a request rather than
 * abstaining — so page 2 of any search comes back {@code 403 "T1: * does not cover Cron"}.
 *
 * <p>Nothing in #34's own tests caught it: they all check a single typed request. Paging was the first thing to
 * issue a request with no type, and it is the common case for every collection a client reads.
 */
class T1PagingRegressionTest extends LiteStackTest {

    static UUID project;
    static String member;

    @BeforeEach
    void aProjectWithEnoughToPage() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "T1Paging"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        member = "T1Paging-" + UUID.randomUUID() + "@bigbook.test";
        // no policy: the default "*" full project access, which is what emits the deny rules
        seedSeat(project, member, seedUser(member), false);
        for (int i = 0; i < 4; i++) {
            call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(member, project),
                    Map.of("resourceType", "Patient", "name", List.of(Map.of("family", "Page" + i))),
                    JsonNode.class);
        }
    }

    @Test
    void followingANextLinkIsPermitted() {
        String token = tokenFor(member, project);
        ResponseEntity<JsonNode> first = call(HttpMethod.GET, "/fhir/R4/Patient?_count=2", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String next = nextLink(first.getBody());
        assertThat(next).as("4 patients with _count=2 must offer a next page").isNotEqualTo("<no next>");

        ResponseEntity<JsonNode> second = call(HttpMethod.GET, pathOf(next), token, null, JsonNode.class,
                "Cache-Control", "no-cache");

        assertThat(second.getStatusCode())
                .as("a paging cursor carries no resource type, and must not match an admin-type deny: %s",
                        second.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("entry")).as("and page 2 must actually return resources").isNotEmpty();
    }

    /** The T1 property #34 exists for must still hold: a `*` policy does not reach an admin type. */
    @Test
    void theAdminTypeDenyStillApplies() {
        ResponseEntity<JsonNode> refused = call(HttpMethod.POST, "/fhir/R4/AccessPolicy", tokenFor(member, project),
                Map.of("resourceType", "AccessPolicy", "name", "self-authored",
                        "resource", List.of(Map.of("resourceType", "*"))),
                JsonNode.class);

        assertThat(refused.getStatusCode())
                .as("fixing paging must not reopen the T1 hole")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static String nextLink(JsonNode bundle) {
        for (JsonNode link : bundle.path("link")) {
            if ("next".equals(link.path("relation").asText())) {
                return link.path("url").asText();
            }
        }
        return "<no next>";
    }

    private static String pathOf(String url) {
        int fhir = url.indexOf("/fhir/R4");
        return fhir < 0 ? url : url.substring(fhir);
    }
}
