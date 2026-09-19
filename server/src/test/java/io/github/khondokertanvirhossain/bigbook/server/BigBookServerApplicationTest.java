package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The HTTP surface issue #2 promised: health, metadata, and a HAPI that really stores. */
class BigBookServerApplicationTest extends LiteStackTest {

    @Test
    void healthIsUp() {
        ResponseEntity<JsonNode> health = http.getForEntity("/actuator/health", JsonNode.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void readinessIsUpOnceBootstrapHasRun() {
        ResponseEntity<JsonNode> readiness = http.getForEntity("/actuator/health/readiness", JsonNode.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void metadataIsAnR4CapabilityStatementOnTheConfiguredBaseUrl() {
        ResponseEntity<JsonNode> metadata = http.getForEntity("/fhir/R4/metadata", JsonNode.class);

        assertThat(metadata.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metadata.getHeaders().getContentType().toString()).startsWith("application/fhir+json");
        assertThat(metadata.getBody().path("resourceType").asText()).isEqualTo("CapabilityStatement");
        assertThat(metadata.getBody().path("fhirVersion").asText()).isEqualTo("4.0.1");
        assertThat(metadata.getBody().path("implementation").path("url").asText())
                .isEqualTo("http://bigbook.test/fhir/R4");
    }

    @Test
    void embeddedHapiStoresAndSearches() {
        String bearer = superAdminToken();
        String family = "Rahman-" + java.util.UUID.randomUUID();
        String patient = "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"" + family + "\"}]}";

        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/Patient", bearer, patient, JsonNode.class);
        ResponseEntity<JsonNode> found = call(HttpMethod.GET, "/fhir/R4/Patient?name=" + family, bearer, null, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(found.getBody().path("total").asInt()).isEqualTo(1);
    }

    @Test
    void theFhirApiNeedsABearerTokenButTheCapabilityStatementDoesNot() {
        assertThat(http.getForEntity("/fhir/R4/Patient", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/admin/projects", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/fhir/R4/metadata", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
