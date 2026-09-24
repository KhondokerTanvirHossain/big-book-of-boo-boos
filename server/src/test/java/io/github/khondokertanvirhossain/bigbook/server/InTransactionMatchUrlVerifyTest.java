package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ADR-001's bounded unknown for the #36 match-URL resolver (Architect, 2026-09-22) — <b>a stop condition, per
 * the PO: measure it before building the resolver.</b>
 *
 * <p>The resolver plan is: at {@code PRECOMMIT}, resolve any match-URL reference by a plain DAO search in the
 * caller's partition, and refuse on zero, multiple, or error. The question that decides whether that is enough:
 *
 * <blockquote>if a conditional reference targets a resource created by <b>another entry of the same bundle</b>,
 * can an in-transaction search see it at the moment that entry's {@code PRECOMMIT} fires?</blockquote>
 *
 * <p>If it cannot, the fail-closed rule refuses a legitimate bundle, and the documented answer is a
 * bundle-scoped pre-write reference map — new machinery, to be priced and reported before building, not folded
 * into the ~60 lines.
 *
 * <p>Two shapes are measured: the target created by an <i>earlier</i> entry (the unknown), and a target that
 * already existed before the bundle (the ordinary case the resolver must handle).
 */
class InTransactionMatchUrlVerifyTest extends LiteStackTest {

    @Autowired
    RestfulServer fhirServer;

    @Autowired
    DaoRegistry daoRegistry;

    static UUID project;
    static String user;

    @BeforeEach
    void oneProjectOneSeat() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "InTxMatchUrl"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "InTx-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
    }

    /** Runs the resolver's own search at PRECOMMIT and records what it finds. */
    @Interceptor
    static class Resolver {
        final List<String> results = new ArrayList<>();
        private final DaoRegistry daos;
        private final String system;
        private final String value;

        Resolver(DaoRegistry daos, String system, String value) {
            this.daos = daos;
            this.system = system;
            this.value = value;
        }

        @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
        public void resolve(IBaseResource resource, RequestDetails request) {
            if (!(resource instanceof org.hl7.fhir.r4.model.Observation)) {
                return;
            }
            SystemRequestDetails search = new SystemRequestDetails();
            search.setRequestPartitionId(RequestPartitionId.fromPartitionId(
                    ((ProjectContext) request.getAttribute(ProjectContext.ATTRIBUTE)).project().partitionId()));
            SearchParameterMap map = SearchParameterMap.newSynchronous()
                    .add("identifier", new TokenParam(system, value));
            try {
                var found = daos.getResourceDao("Patient").search(map, search).getAllResources();
                results.add("matches=" + found.size()
                        + (found.isEmpty() ? "" : " -> " + found.get(0).getIdElement().toUnqualifiedVersionless()));
            } catch (RuntimeException failed) {
                results.add("THREW " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
        }
    }

    /** The ordinary case: the target existed before the bundle. The resolver must find exactly one. */
    @Test
    void aTargetThatExistedBeforeTheBundleIsFound() {
        String marker = "pre-existing-" + UUID.randomUUID();
        call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(user, project),
                Map.of("resourceType", "Patient",
                        "identifier", List.of(Map.of("system", "urn:bigbook:intx", "value", marker)),
                        "name", List.of(Map.of("family", "PreExisting"))),
                JsonNode.class);

        Resolver resolver = new Resolver(daoRegistry, "urn:bigbook:intx", marker);
        fhirServer.registerInterceptor(resolver);
        try {
            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project),
                    observationReferencing("urn:bigbook:intx|" + marker), JsonNode.class);
            System.out.println("VERIFY-INTX pre-existing target: bundle=" + response.getStatusCode()
                    + " resolver=" + resolver.results);
            assertThat(resolver.results)
                    .as("the ordinary case must resolve to exactly one, or the resolver plan does not work at all")
                    .anySatisfy(result -> assertThat(result).startsWith("matches=1"));
        } finally {
            fhirServer.unregisterInterceptor(resolver);
        }
    }

    /**
     * THE STOP CONDITION: the target is created by an earlier entry of the <i>same</i> bundle. If the
     * in-transaction search cannot see it, the fail-closed rule refuses a legitimate write.
     */
    @Test
    void aTargetCreatedByAnEarlierEntryOfTheSameBundle() {
        String marker = "same-bundle-" + UUID.randomUUID();
        Resolver resolver = new Resolver(daoRegistry, "urn:bigbook:intx", marker);
        fhirServer.registerInterceptor(resolver);
        try {
            Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "transaction",
                    "entry", List.of(
                            // entry 1 creates the Patient the conditional reference will name
                            Map.of("request", Map.of("method", "POST", "url", "Patient"),
                                    "resource", Map.of("resourceType", "Patient",
                                            "identifier", List.of(Map.of("system", "urn:bigbook:intx",
                                                    "value", marker)),
                                            "name", List.of(Map.of("family", "SameBundle")))),
                            // entry 2 references it by match URL, not by urn:uuid
                            Map.of("request", Map.of("method", "POST", "url", "Observation"),
                                    "resource", Map.of("resourceType", "Observation", "status", "final",
                                            "code", Map.of("text", "same-bundle probe"),
                                            "subject", Map.of("reference",
                                                    "Patient?identifier=urn:bigbook:intx|" + marker)))));

            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project), bundle,
                    JsonNode.class);
            System.out.println("VERIFY-INTX same-bundle target: bundle=" + response.getStatusCode()
                    + " resolver=" + resolver.results);
            System.out.println("VERIFY-INTX same-bundle body=" + String.valueOf(response.getBody()).substring(0,
                    Math.min(400, String.valueOf(response.getBody()).length())));
            // MEASURED 2026-09-24: HAPI refuses the bundle itself, at 404 HAPI-1091 "Invalid match URL … No
            // resources match this search", BEFORE any PRECOMMIT fires — the resolver list is empty. So a
            // conditional reference to a same-bundle target is not a legal write on 8.12.1, there is no
            // legitimate bundle for the resolver to refuse, and the bundle-scoped pre-write reference map the
            // decision held in reserve is not needed. The stop condition does not fire.
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("HAPI itself refuses this shape: %s", response.getBody())
                    .isFalse();
            assertThat(String.valueOf(response.getBody()))
                    .as("and it refuses for the reason that makes the resolver sufficient")
                    .contains("HAPI-1091");
            assertThat(resolver.results)
                    .as("no PRECOMMIT fires, so Big Book never gets the chance to over-refuse")
                    .isEmpty();
        } finally {
            fhirServer.unregisterInterceptor(resolver);
        }
    }

    private static Map<String, Object> observationReferencing(String identifier) {
        return Map.of("resourceType", "Bundle", "type", "transaction",
                "entry", List.of(Map.of(
                        "request", Map.of("method", "POST", "url", "Observation"),
                        "resource", Map.of("resourceType", "Observation", "status", "final",
                                "code", Map.of("text", "intx probe"),
                                "subject", Map.of("reference", "Patient?identifier=" + identifier)))));
    }
}
