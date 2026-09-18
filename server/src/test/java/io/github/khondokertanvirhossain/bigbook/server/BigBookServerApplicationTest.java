package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The HTTP surface issue #2 promised: health, metadata, and a HAPI that really stores. */
class BigBookServerApplicationTest extends LiteStackTest {

    @Autowired
    TestRestTemplate http;

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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        String patient = "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Rahman\"}]}";

        ResponseEntity<JsonNode> created =
                http.postForEntity("/fhir/R4/Patient", new HttpEntity<>(patient, headers), JsonNode.class);
        ResponseEntity<JsonNode> found = http.getForEntity("/fhir/R4/Patient?name=Rahman", JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(found.getBody().path("total").asInt()).isEqualTo(1);
    }
}
