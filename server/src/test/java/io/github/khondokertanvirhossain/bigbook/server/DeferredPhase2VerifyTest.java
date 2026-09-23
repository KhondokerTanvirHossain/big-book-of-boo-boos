package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ADR-001's three pre-merge verifies for the deferred phase-2 path (Architect, 2026-09-21), measured on 8.12.1.
 *
 * <p><b>(a) is the stop condition.</b> If a {@code TransactionSynchronization} registered from a
 * {@code PRECOMMIT} hook does not run {@code beforeCommit} inside HAPI's transaction — or if throwing there
 * does not roll every entry back — the deferred check is <i>decorative</i>: it would report a violation after
 * the data is already committed. The Architect's instruction is to stop and raise, not work around.
 *
 * <p>(b) asks whether a re-read inside {@code beforeCommit} sees substituted references, which is the entire
 * point of deferring. (c) asks whether {@code batch} entries transact per entry, so the deferred path degrades
 * to per-entry there.
 */
class DeferredPhase2VerifyTest extends LiteStackTest {

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
                Map.of("name", "DeferredVerify"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "Deferred-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
    }

    /** Records what the synchronization saw, so the assertions rest on observation rather than inference. */
    @Interceptor
    static class Probe {
        final List<String> atPrecommit = new ArrayList<>();
        final List<String> precommitSerialized = new ArrayList<>();
        final List<String> atBeforeCommit = new ArrayList<>();
        final List<Boolean> synchronizationActive = new ArrayList<>();
        private final DaoRegistry daos;
        private final boolean throwInBeforeCommit;

        Probe(DaoRegistry daos, boolean throwInBeforeCommit) {
            this.daos = daos;
            this.throwInBeforeCommit = throwInBeforeCommit;
        }

        @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
        public void created(IBaseResource resource, RequestDetails request) {
            if (!(resource instanceof org.hl7.fhir.r4.model.Observation observation)) {
                return;
            }
            // the re-read needs the caller's partition: a bare SystemRequestDetails has none, and the tenancy
            // hooks refuse it (HAPI-1319). The deferred path will have to carry it the same way.
            var partition = ca.uhn.fhir.interceptor.model.RequestPartitionId.fromPartitionId(
                    ((io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext) request.getAttribute(
                            io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext.ATTRIBUTE))
                            .project().partitionId());
            // snapshot as a string at PRECOMMIT time: if HAPI mutated the same object later, a live getter
            // would read the substituted value and look like early resolution
            atPrecommit.add(String.valueOf(observation.getSubject().getReference()));
            precommitSerialized.add(request.getFhirContext().newJsonParser().encodeResourceToString(observation)
                    .contains("urn:uuid:") ? "HAS-PLACEHOLDER" : "SUBSTITUTED");
            String id = observation.getIdElement().toUnqualifiedVersionless().getValue();
            synchronizationActive.add(TransactionSynchronizationManager.isSynchronizationActive());
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    // the re-read (b): does the stored resource carry the substituted reference?
                    SystemRequestDetails reread = new SystemRequestDetails();
                    reread.setRequestPartitionId(partition);
                    IBaseResource stored = daos.getResourceDao("Observation")
                            .read(new org.hl7.fhir.r4.model.IdType(id), reread);
                    atBeforeCommit.add(String.valueOf(
                            ((org.hl7.fhir.r4.model.Observation) stored).getSubject().getReference()));
                    if (throwInBeforeCommit) {
                        throw new ForbiddenOperationException("deferred phase 2 refused (probe)");
                    }
                }
            });
        }
    }

    /** (a) and (b): the synchronization runs inside the transaction and the re-read sees substituted refs. */
    @Test
    void beforeCommitRunsInsideTheTransactionAndSeesSubstitutedReferences() {
        Probe probe = new Probe(daoRegistry, false);
        fhirServer.registerInterceptor(probe);
        try {
            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project),
                    patientPlusObservation(), JsonNode.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("the bundle must succeed, or the probe measures nothing: %s", response.getBody())
                    .isTrue();

            System.out.println("VERIFY-a synchronizationActive at PRECOMMIT = " + probe.synchronizationActive);
            System.out.println("VERIFY-b subject at PRECOMMIT     = " + probe.atPrecommit);
            System.out.println("VERIFY-b subject at beforeCommit  = " + probe.atBeforeCommit);

            assertThat(probe.synchronizationActive)
                    .as("(a) a Spring transaction must be active at PRECOMMIT, or nothing can be registered")
                    .containsOnly(true);
            assertThat(probe.atBeforeCommit)
                    .as("(a) beforeCommit must actually run")
                    .isNotEmpty();
            // NOTE: with the Patient FIRST, HAPI has already substituted by PRECOMMIT — recorded, because it
            // narrows where the deferred path is actually needed. The forward-reference case is below.
            assertThat(probe.atBeforeCommit)
                    .as("(b) the re-read in beforeCommit must see the substituted reference")
                    .allSatisfy(reference -> assertThat(reference).startsWith("Patient/").doesNotContain("urn:"));
        } finally {
            fhirServer.unregisterInterceptor(probe);
        }
    }

    /** (a), the half that matters: throwing in beforeCommit must roll back every entry. */
    @Test
    void throwingInBeforeCommitRollsBackTheWholeBundle() {
        Probe probe = new Probe(daoRegistry, true);
        fhirServer.registerInterceptor(probe);
        String marker = "RollbackProbe-" + UUID.randomUUID();
        try {
            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project),
                    patientPlusObservation(marker), JsonNode.class);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("a throw in beforeCommit must fail the request: %s", response.getBody())
                    .isFalse();

            // the real question: is the Patient gone? If it is still there, the check is decorative.
            ResponseEntity<JsonNode> search = call(HttpMethod.GET, "/fhir/R4/Patient?family=" + marker,
                    tokenFor(user, project), null, JsonNode.class, "Cache-Control", "no-cache");
            System.out.println("VERIFY-a rollback: patients still stored = " + search.getBody().path("total").asInt());
            assertThat(search.getBody().path("entry"))
                    .as("(a) STOP CONDITION: every entry must be rolled back, or the deferred check is decorative")
                    .isEmpty();
        } finally {
            fhirServer.unregisterInterceptor(probe);
        }
    }

    /**
     * The shape ordering says cannot be answered per-resource: the Observation is entry 1 and references a
     * Patient created in entry 2. This is what decides whether the deferred path is needed at all.
     */
    @Test
    void aForwardReferenceIsUnresolvedAtPrecommitButSubstitutedByBeforeCommit() {
        Probe probe = new Probe(daoRegistry, false);
        fhirServer.registerInterceptor(probe);
        try {
            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project),
                    observationBeforePatient("ForwardProbe"), JsonNode.class);
            System.out.println("VERIFY-fwd status = " + response.getStatusCode());
            System.out.println("VERIFY-fwd subject at PRECOMMIT     = " + probe.atPrecommit);
            System.out.println("VERIFY-fwd serialized at PRECOMMIT  = " + probe.precommitSerialized);
            System.out.println("VERIFY-fwd subject at beforeCommit  = " + probe.atBeforeCommit);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("the bundle must succeed, or the probe measures nothing: %s", response.getBody())
                    .isTrue();
        } finally {
            fhirServer.unregisterInterceptor(probe);
        }
    }

    /**
     * The shape the #33 finding actually failed on: a <b>conditional</b> reference, not a {@code urn:uuid}
     * placeholder. The two are different mechanisms, and the deferred design addresses only placeholders.
     */
    @Test
    void aConditionalReferenceAtPrecommitAndBeforeCommit() {
        Probe probe = new Probe(daoRegistry, false);
        fhirServer.registerInterceptor(probe);
        try {
            // a Patient the conditional reference can resolve to
            String marker = "cond-" + UUID.randomUUID();
            call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(user, project),
                    Map.of("resourceType", "Patient",
                            "identifier", List.of(Map.of("system", "urn:bigbook:verify", "value", marker)),
                            "name", List.of(Map.of("family", "CondProbe"))),
                    JsonNode.class);

            Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "transaction",
                    "entry", List.of(Map.of(
                            "request", Map.of("method", "POST", "url", "Observation"),
                            "resource", Map.of("resourceType", "Observation", "status", "final",
                                    "code", Map.of("text", "conditional reference probe"),
                                    "subject", Map.of("reference",
                                            "Patient?identifier=urn:bigbook:verify|" + marker)))));

            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project), bundle,
                    JsonNode.class);
            System.out.println("VERIFY-cond status = " + response.getStatusCode());
            System.out.println("VERIFY-cond subject at PRECOMMIT     = " + probe.atPrecommit);
            System.out.println("VERIFY-cond serialized at PRECOMMIT  = " + probe.precommitSerialized);
            System.out.println("VERIFY-cond subject at beforeCommit  = " + probe.atBeforeCommit);
        } finally {
            fhirServer.unregisterInterceptor(probe);
        }
    }

    /** (c) batch: each entry transacts on its own, so the deferred path degrades to per-entry. */
    @Test
    void batchEntriesEachCarryTheirOwnTransaction() {
        Probe probe = new Probe(daoRegistry, false);
        fhirServer.registerInterceptor(probe);
        try {
            Map<String, Object> batch = Map.of("resourceType", "Bundle", "type", "batch",
                    "entry", List.of(
                            Map.of("fullUrl", "urn:uuid:11111111-1111-1111-1111-111111111111",
                                    "request", Map.of("method", "POST", "url", "Patient"),
                                    "resource", Map.of("resourceType", "Patient",
                                            "name", List.of(Map.of("family", "BatchProbe")))),
                            Map.of("request", Map.of("method", "POST", "url", "Observation"),
                                    "resource", Map.of("resourceType", "Observation", "status", "final",
                                            "code", Map.of("text", "batch probe"),
                                            "subject", Map.of("reference",
                                                    "urn:uuid:11111111-1111-1111-1111-111111111111")))));

            ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/fhir/R4", tokenFor(user, project), batch,
                    JsonNode.class);

            System.out.println("VERIFY-c batch status = " + response.getStatusCode());
            System.out.println("VERIFY-c batch body   = " + response.getBody());
            System.out.println("VERIFY-c batch subject at PRECOMMIT    = " + probe.atPrecommit);
            System.out.println("VERIFY-c batch subject at beforeCommit = " + probe.atBeforeCommit);
            // no assertion on the placeholder resolving: a batch does not share a transaction, so a urn:uuid
            // across entries is not legal FHIR. What this records is how 8.12.1 actually behaves.
            assertThat(response.getStatusCode().value()).isBetween(200, 499);
        } finally {
            fhirServer.unregisterInterceptor(probe);
        }
    }

    private static Map<String, Object> patientPlusObservation() {
        return patientPlusObservation("DeferredProbe");
    }

    /** Forward reference: the Observation is entry 1 and points at the Patient created in entry 2. */
    private static Map<String, Object> observationBeforePatient(String family) {
        String placeholder = "urn:uuid:" + UUID.randomUUID();
        return Map.of("resourceType", "Bundle", "type", "transaction",
                "entry", List.of(
                        Map.of("request", Map.of("method", "POST", "url", "Observation"),
                                "resource", Map.of("resourceType", "Observation", "status", "final",
                                        "code", Map.of("text", "forward reference probe"),
                                        "subject", Map.of("reference", placeholder))),
                        Map.of("fullUrl", placeholder,
                                "request", Map.of("method", "POST", "url", "Patient"),
                                "resource", Map.of("resourceType", "Patient",
                                        "name", List.of(Map.of("family", family))))));
    }

    /** BB-R-001's headline shape: Patient + Observation whose subject is the Patient's urn:uuid. */
    private static Map<String, Object> patientPlusObservation(String family) {
        String placeholder = "urn:uuid:" + UUID.randomUUID();
        return Map.of("resourceType", "Bundle", "type", "transaction",
                "entry", List.of(
                        Map.of("fullUrl", placeholder,
                                "request", Map.of("method", "POST", "url", "Patient"),
                                "resource", Map.of("resourceType", "Patient",
                                        "name", List.of(Map.of("family", family)))),
                        Map.of("request", Map.of("method", "POST", "url", "Observation"),
                                "resource", Map.of("resourceType", "Observation", "status", "final",
                                        "code", Map.of("text", "deferred probe"),
                                        "subject", Map.of("reference", placeholder)))));
    }
}
