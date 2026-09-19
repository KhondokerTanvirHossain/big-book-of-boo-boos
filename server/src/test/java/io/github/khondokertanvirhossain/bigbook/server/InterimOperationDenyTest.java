package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * INTERIM, with {@link InterimOperationDenyInterceptor}: issue #7 deletes both. The deny matches on the
 * operation name HAPI reports, so it would fail open if that string changed; these tests are what catches it.
 */
class InterimOperationDenyTest extends LiteStackTest {

    @Test
    void aProjectUserCannotReachTheDangerousOperations() {
        UUID project = UUID.fromString(call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                java.util.Map.of("name", "Deny"), JsonNode.class).getBody().path("id").asText());
        String email = "deny-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, email, seedUser(email), true);
        String member = tokenFor(email, project);
        String patient = call(HttpMethod.POST, "/fhir/R4/Patient", member, "{\"resourceType\":\"Patient\"}", JsonNode.class)
                .getBody().path("id").asText();

        // The five that are live on this server. $expunge is 405 "not enabled" by HAPI default and
        // hapi.fhir.merge is not routed at all (both measured, issue #8) — they are on the deny list
        // anyway, because enabling them later must not silently open them to project users.
        assertThat(status("/fhir/R4/$get-resource-counts", member, null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/$mark-all-resources-for-reindexing", member, PARAMETERS)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/$perform-reindexing-pass", member, PARAMETERS)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/$reindex-terminology", member, PARAMETERS)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/Patient/" + patient + "/$meta-add", member, META)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/Patient/" + patient + "/$meta-delete", member, META)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/fhir/R4/$hapi.fhir.replace-references", member, PARAMETERS)).isEqualTo(HttpStatus.FORBIDDEN);
        // and $expunge is refused before HAPI can answer 405, so enabling it changes nothing here
        assertThat(status("/fhir/R4/Patient/" + patient + "/$expunge", member, expunge())).isEqualTo(HttpStatus.FORBIDDEN);

        // the resource is still there: the deny happens before HAPI does anything
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + patient, member, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSuperAdminStillReachesThem() {
        String superAdmin = superAdminToken();

        ResponseEntity<JsonNode> counts = call(HttpMethod.GET, "/fhir/R4/$get-resource-counts", superAdmin, null, JsonNode.class);
        ResponseEntity<JsonNode> reindex = call(HttpMethod.POST, "/fhir/R4/$mark-all-resources-for-reindexing", superAdmin, PARAMETERS, JsonNode.class);

        assertThat(counts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(counts.getBody().path("resourceType").asText()).isEqualTo("Parameters");
        assertThat(reindex.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Operations the contract keeps: a project user must still be able to call them. */
    @Test
    void theIntendedOperationsAreNotDenied() {
        String superAdmin = superAdminToken();

        for (String allowed : List.of(
                "/fhir/R4/Patient/$validate",
                "/fhir/R4/ValueSet/$expand?url=http://hl7.org/fhir/ValueSet/administrative-gender")) {
            HttpStatus status = allowed.contains("$validate")
                    ? status(allowed, superAdmin, "{\"resourceType\":\"Patient\"}")
                    : HttpStatus.valueOf(call(HttpMethod.GET, allowed, superAdmin, null, String.class).getStatusCode().value());
            assertThat(status).as(allowed).isEqualTo(HttpStatus.OK);
        }
    }

    private static final String PARAMETERS = "{\"resourceType\":\"Parameters\"}";

    private static final String META =
            "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"meta\",\"valueMeta\":{\"tag\":[{\"code\":\"x\"}]}}]}";

    private static String expunge() {
        return "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"expungeDeletedResources\",\"valueBoolean\":true}]}";
    }

    private HttpStatus status(String url, String bearer, String body) {
        ResponseEntity<String> response = body == null
                ? call(HttpMethod.GET, url, bearer, null, String.class)
                : call(HttpMethod.POST, url, bearer, body, String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
