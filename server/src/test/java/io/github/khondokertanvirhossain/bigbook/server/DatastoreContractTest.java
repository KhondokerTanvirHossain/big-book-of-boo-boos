package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Issue #8: BB-R-001's datastore contract, and the BB-R-014.1 status codes that come with it. */
class DatastoreContractTest extends LiteStackTest {

    @Test
    void idsAreServerAssignedUuids() {
        JsonNode created = create(patient("Uuid")).getBody();

        assertThat(created.path("id").asText()).matches("[0-9a-f-]{36}");
        // a client-supplied id on a create is ignored, not honoured and not an error (as Medplum does)
        JsonNode withClientId = call(HttpMethod.POST, "/fhir/R4/Patient", token(), "{\"resourceType\":\"Patient\",\"id\":\"chosen\"}", JsonNode.class).getBody();
        assertThat(withClientId.path("id").asText()).isNotEqualTo("chosen").matches("[0-9a-f-]{36}");
    }

    @Test
    void updateAsCreateIs404ForAProjectUserAndTheIdNeverLeaks() {
        String fresh = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> put = call(HttpMethod.PUT, "/fhir/R4/Patient/" + fresh, token(),
                "{\"resourceType\":\"Patient\",\"id\":\"" + fresh + "\"}", JsonNode.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + fresh, token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void historyDeleteAndGone() {
        String id = create(patient("History")).getBody().path("id").asText();
        call(HttpMethod.PUT, "/fhir/R4/Patient/" + id, token(), "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\",\"gender\":\"female\"}", JsonNode.class);

        JsonNode history = call(HttpMethod.GET, "/fhir/R4/Patient/" + id + "/_history", token(), null, JsonNode.class).getBody();
        assertThat(history.path("entry")).hasSize(2);

        assertThat(call(HttpMethod.DELETE, "/fhir/R4/Patient/" + id, token(), null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + id, token(), null, String.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient/" + UUID.randomUUID(), token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Parity rows a, a2, a3: the three conditional-update outcomes Medplum defines. */
    @Test
    void conditionalUpdate() {
        String mrn = "mrn-" + UUID.randomUUID();
        String body = "{\"resourceType\":\"Patient\",\"identifier\":[{\"value\":\"" + mrn + "\"}]}";

        ResponseEntity<JsonNode> withId = call(HttpMethod.PUT, "/fhir/R4/Patient?identifier=" + mrn, token(),
                "{\"resourceType\":\"Patient\",\"id\":\"" + UUID.randomUUID() + "\",\"identifier\":[{\"value\":\"" + mrn + "\"}]}", JsonNode.class);
        assertThat(withId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withId.getBody().path("issue").path(0).path("diagnostics").asText()).contains("client-assigned ID");

        ResponseEntity<JsonNode> created = call(HttpMethod.PUT, "/fhir/R4/Patient?identifier=" + mrn, token(), body, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> updated = call(HttpMethod.PUT, "/fhir/R4/Patient?identifier=" + mrn, token(), body, JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().path("id").asText()).isEqualTo(created.getBody().path("id").asText());

        create(body); // a second match
        assertThat(call(HttpMethod.PUT, "/fhir/R4/Patient?identifier=" + mrn, token(), body, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
        // and a conditional delete matching many refuses, rather than deleting them all (T19)
        assertThat(call(HttpMethod.DELETE, "/fhir/R4/Patient?identifier=" + mrn, token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void conditionalCreateReturnsTheExistingResource() {
        String mrn = "mrn-" + UUID.randomUUID();
        String body = "{\"resourceType\":\"Patient\",\"identifier\":[{\"value\":\"" + mrn + "\"}]}";

        ResponseEntity<JsonNode> first = call(HttpMethod.POST, "/fhir/R4/Patient", token(), body, JsonNode.class, "If-None-Exist", "identifier=" + mrn);
        ResponseEntity<JsonNode> second = call(HttpMethod.POST, "/fhir/R4/Patient", token(), body, JsonNode.class, "If-None-Exist", "identifier=" + mrn);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("id").asText()).isEqualTo(first.getBody().path("id").asText());
    }

    @Test
    void transactionsAreAtomicAndResolveUrnUuid() {
        String urn = "urn:uuid:" + UUID.randomUUID();
        String bundle = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"fullUrl\":\"" + urn + "\",\"resource\":{\"resourceType\":\"Patient\"},\"request\":{\"method\":\"POST\",\"url\":\"Patient\"}},"
                + "{\"resource\":{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"tx\"},\"subject\":{\"reference\":\"" + urn + "\"}},"
                + "\"request\":{\"method\":\"POST\",\"url\":\"Observation\"}}]}";

        JsonNode response = call(HttpMethod.POST, "/fhir/R4", token(), bundle, JsonNode.class).getBody();
        assertThat(response.path("entry")).hasSize(2);
        String patientId = response.path("entry").path(0).path("response").path("location").asText();
        JsonNode observation = call(HttpMethod.GET, "/fhir/R4/" + response.path("entry").path(1).path("response").path("location").asText().replaceAll("/_history.*", ""), token(), null, JsonNode.class).getBody();
        assertThat(observation.path("subject").path("reference").asText()).isEqualTo(patientId.replaceAll("/_history.*", ""));

        int before = count("Patient");
        // the second entry must fail at *storage*: HAPI does not run profile validation on write, so an
        // incomplete resource is stored happily; a dangling reference is a real failure (BB-R-001.6)
        String rollback = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                + "{\"resource\":{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Rollback\"}]},\"request\":{\"method\":\"POST\",\"url\":\"Patient\"}},"
                + "{\"resource\":{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"x\"},"
                + "\"subject\":{\"reference\":\"Patient/" + UUID.randomUUID() + "\"}},\"request\":{\"method\":\"POST\",\"url\":\"Observation\"}}]}";

        assertThat(call(HttpMethod.POST, "/fhir/R4", token(), rollback, JsonNode.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(count("Patient")).as("the first entry must not survive its bundle").isEqualTo(before);
        assertThat(searchCount("Patient?family=Rollback")).isZero();
    }

    @Test
    void referentialIntegrityIsOnByDefault() {
        ResponseEntity<JsonNode> dangling = call(HttpMethod.POST, "/fhir/R4/Observation", token(),
                "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"x\"},\"subject\":{\"reference\":\"Patient/" + UUID.randomUUID() + "\"}}",
                JsonNode.class);

        assertThat(dangling.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dangling.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
    }

    @Test
    void validateTakesABareResourceBody() {
        ResponseEntity<JsonNode> valid = call(HttpMethod.POST, "/fhir/R4/Patient/$validate", token(), patient("Valid"), JsonNode.class);
        ResponseEntity<JsonNode> invalid = call(HttpMethod.POST, "/fhir/R4/Observation/$validate", token(),
                "{\"resourceType\":\"Observation\",\"status\":\"final\"}", JsonNode.class);

        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invalid.getBody().path("issue").toString()).contains("error").contains("Observation.code");
    }

    @Test
    void expandResolvesBaseR4ValueSets() {
        JsonNode expanded = call(HttpMethod.GET, "/fhir/R4/ValueSet/$expand?url=http://hl7.org/fhir/ValueSet/administrative-gender",
                token(), null, JsonNode.class).getBody();

        assertThat(expanded.path("resourceType").asText()).isEqualTo("ValueSet");
        assertThat(expanded.path("expansion").path("contains")).hasSize(4);
    }

    @Test
    void jsonPatchAndHeaders() {
        String id = create(patient("Patch")).getBody().path("id").asText();

        ResponseEntity<JsonNode> patched = call(HttpMethod.PATCH, "/fhir/R4/Patient/" + id, token(),
                java.util.List.of(Map.of("op", "add", "path", "/gender", "value", "female")), JsonNode.class, "Content-Type", "application/json-patch+json");
        ResponseEntity<JsonNode> read = call(HttpMethod.GET, "/fhir/R4/Patient/" + id, token(), null, JsonNode.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().path("gender").asText()).isEqualTo("female");
        assertThat(read.getHeaders().getFirst("ETag")).isEqualTo("W/\"2\"");
        assertThat(read.getHeaders().getContentType().toString()).startsWith("application/fhir+json");
    }

    private String token() {
        return superAdminToken();
    }

    private ResponseEntity<JsonNode> create(String body) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/Patient", token(), body, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created;
    }

    private int searchCount(String query) {
        return call(HttpMethod.GET, "/fhir/R4/" + query + "&_summary=count", token(), null, JsonNode.class, "Cache-Control", "no-cache")
                .getBody().path("total").asInt();
    }

    private int count(String type) {
        return call(HttpMethod.GET, "/fhir/R4/" + type + "?_summary=count", token(), null, JsonNode.class, "Cache-Control", "no-cache")
                .getBody().path("total").asInt();
    }

    private static String patient(String family) {
        return "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"" + family + "\"}]}";
    }
}
