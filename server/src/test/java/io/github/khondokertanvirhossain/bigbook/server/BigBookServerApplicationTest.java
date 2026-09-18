package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Boots the server against the Postgres image lite pins, laid out the way lite.yml lays it out. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "bigbook.base-url=http://bigbook.test/")
class BigBookServerApplicationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse(System.getProperty("bigbook.test.postgres-image"))
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("bigbook")
            .withUsername("bigbook")
            .withUrlParam("currentSchema", "hapi")
            .withInitScript("create-hapi-schema.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void healthIsUp() {
        ResponseEntity<JsonNode> health = http.getForEntity("/actuator/health", JsonNode.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
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
