package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * ADR-001 Open (2026-09-19), items (d) and (e): the two write-check facts left unmeasured when the phase-2
 * pointcut was chosen. Measured rather than assumed, because the answer decides whether the write check can
 * see what it is checking.
 *
 * <p>(d) In a transaction, does a <b>conditional reference</b> ({@code Patient?identifier=…}) reach
 * {@code PRECOMMIT_*} already rewritten to a literal id? If not, a criterion on that reference is unanswerable
 * at the pointcut that is supposed to answer it.
 *
 * <p>(e) Does {@code PRECOMMIT_RESOURCE_DELETED} fire, and with what? Phase 1 catches a delete at
 * {@code PRESTORAGE}, so a "no" here is not fatal — but an unmeasured "maybe" is.
 */
class WriteCheckPointcutVerifyTest extends LiteStackTest {

    @Autowired
    RestfulServer fhirServer;

    static UUID project;
    static String user;

    @BeforeEach
    void oneProjectOneSeat() {
        if (project != null) {
            return;
        }
        String superAdmin = superAdminToken();
        ResponseEntity<JsonNode> created =
                call(HttpMethod.POST, "/admin/projects", superAdmin, Map.of("name", "WriteCheckProbe"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "WriteCheck-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
    }

    private String create(String bearer, String type, Object body) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/" + type, bearer, body, JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(String.valueOf(created.getBody())).isTrue();
        return type + "/" + created.getBody().path("id").asText();
    }

    @Interceptor
    static class Recorder {
        final List<String> prestorageCreated = new ArrayList<>();
        final List<String> precommitCreated = new ArrayList<>();
        final List<String> precommitDeleted = new ArrayList<>();

        @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
        public void created(IBaseResource resource, RequestDetails request) {
            prestorageCreated.add(subjectOf(resource));
        }

        @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
        public void committed(IBaseResource resource, RequestDetails request) {
            precommitCreated.add(subjectOf(resource));
        }

        @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
        public void deleted(IBaseResource resource, IIdType id, RequestDetails request) {
            precommitDeleted.add(id == null ? "null-id" : id.getValue());
        }

        private static String subjectOf(IBaseResource resource) {
            if (resource instanceof org.hl7.fhir.r4.model.Observation observation) {
                return observation.getSubject().getReference();
            }
            return resource.fhirType();
        }
    }

    @Test
    void aConditionalReferenceIsResolvedByPrecommitButNotByPrestorage() {
        Recorder recorder = new Recorder();
        fhirServer.registerInterceptor(recorder);
        try {
            String token = tokenFor(user, project);
            // the Patient this conditional reference must resolve to
            create(token, "Patient", Map.of("resourceType", "Patient",
                    "identifier", List.of(Map.of("system", "urn:bigbook:test", "value", "cond-ref-target"))));

            Map<String, Object> bundle = Map.of(
                    "resourceType", "Bundle", "type", "transaction",
                    "entry", List.of(Map.of(
                            "request", Map.of("method", "POST", "url", "Observation"),
                            "resource", Map.of("resourceType", "Observation", "status", "final",
                                    "code", Map.of("text", "conditional reference probe"),
                                    // the conditional reference: HAPI must resolve this to a literal id
                                    "subject", Map.of("reference", "Patient?identifier=urn:bigbook:test|cond-ref-target")))));

            var response = call(HttpMethod.POST, "/fhir/R4", token, bundle, JsonNode.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("the transaction itself must succeed, or the probe measures nothing: %s", response.getBody())
                    .isTrue();

            System.out.println("PROBE-D prestorage subject = " + recorder.prestorageCreated);
            System.out.println("PROBE-D precommit  subject = " + recorder.precommitCreated);

            assertThat(recorder.precommitCreated)
                    .as("(d) at PRECOMMIT a conditional reference must already be a literal Patient/<id>")
                    .anySatisfy(reference -> assertThat(reference).startsWith("Patient/").doesNotContain("?"));
        } finally {
            fhirServer.unregisterInterceptor(recorder);
        }
    }

    @Test
    void precommitResourceDeletedFiresWithTheDeletedId() {
        Recorder recorder = new Recorder();
        fhirServer.registerInterceptor(recorder);
        try {
            String token = tokenFor(user, project);
            String patient = create(token, "Patient", Map.of("resourceType", "Patient",
                    "identifier", List.of(Map.of("system", "urn:bigbook:test", "value", "delete-probe"))));
            call(HttpMethod.DELETE, "/fhir/R4/" + patient, token, null, JsonNode.class);

            System.out.println("PROBE-E precommit deleted = " + recorder.precommitDeleted);

            assertThat(recorder.precommitDeleted)
                    .as("(e) PRECOMMIT_RESOURCE_DELETED must fire and carry the id that was deleted")
                    .isNotEmpty();
        } finally {
            fhirServer.unregisterInterceptor(recorder);
        }
    }
}
