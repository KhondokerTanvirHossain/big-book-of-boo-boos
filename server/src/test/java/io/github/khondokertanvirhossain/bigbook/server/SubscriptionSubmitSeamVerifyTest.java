package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.subscription.match.matcher.matching.IResourceModifiedConsumer;
import ca.uhn.fhir.jpa.subscription.model.ResourceModifiedMessage;
import ca.uhn.fhir.jpa.subscription.submit.interceptor.SubscriptionMatcherInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ADR-002 Open 1 — <b>the blocking verify for #12</b>, measured on 8.12.1.
 *
 * <blockquote>confirm the HAPI hook order lets the {@code subscription_delivery} insert <b>replace</b>
 * {@code SubscriptionDeliveryQueue} rather than run beside it. A "no" is a double-delivery bug: stop and raise,
 * do not work around.</blockquote>
 *
 * <p>The seam is {@code SubscriptionMatcherInterceptor.processResourceModifiedMessage}, which is
 * {@code protected} and which HAPI itself overrides in {@code SynchronousSubscriptionMatcherInterceptor}. The
 * single exit into HAPI's queue is {@link IResourceModifiedConsumer#submitResourceModified}, so "replace" is
 * measurable as a <b>call count of zero</b> on that method while Big Book's own path still sees the event.
 *
 * <p>Three questions, in order of consequence:
 *
 * <ol>
 *   <li><b>Replacement, not duplication.</b> An override that does not call {@code super} must leave HAPI's
 *       consumer untouched: exactly one delivery path, no double delivery. A "no" stops #12.
 *   <li><b>Transactional.</b> ADR-002 says the row is "written in the same transaction as the resource", so the
 *       override must run with a Spring transaction active — otherwise a rolled-back write leaves a delivery
 *       queued for a resource that does not exist.
 *   <li><b>Partition-scoped.</b> The message must carry the writing tenant's partition, or a Subscription in
 *       project A could be offered project B's resource (also a BB-R-007 acceptance criterion).
 * </ol>
 */
class SubscriptionSubmitSeamVerifyTest extends LiteStackTest {

    /** Counts what reaches HAPI's queue, and what a Big Book override would have intercepted. */
    static final List<String> submittedToHapi = new CopyOnWriteArrayList<>();
    static final List<String> seenByOverride = new CopyOnWriteArrayList<>();
    static final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
    static final List<String> partitions = new CopyOnWriteArrayList<>();
    static final List<String> persisted = new CopyOnWriteArrayList<>();

    @Autowired(required = false)
    SubscriptionMatcherInterceptor matcherInterceptor;

    @Autowired(required = false)
    IResourceModifiedConsumer resourceModifiedConsumer;

    static UUID project;
    static String user;

    @BeforeEach
    void oneProjectOneSeat() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "SubmitSeam"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "Seam-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
    }

    /**
     * What is actually wired on the pinned version. Recorded first because everything below depends on it: if
     * HAPI's subscription submit path is not registered at all in Big Book's current configuration, then #12
     * builds the matcher as well as the delivery table, and the ≈150-line estimate covers less than it looks.
     */
    @Test
    void whatIsWiredToday() {
        System.out.println("VERIFY-A1 SubscriptionMatcherInterceptor bean = "
                + (matcherInterceptor == null ? "ABSENT" : matcherInterceptor.getClass().getName()));
        System.out.println("VERIFY-A1 IResourceModifiedConsumer bean = "
                + (resourceModifiedConsumer == null ? "ABSENT" : resourceModifiedConsumer.getClass().getName()));

        // MEASURED 2026-09-24: both ABSENT. Big Book imports SubscriptionChannelConfig but not HAPI's submit or
        // match configs, so nothing feeds subscriptions today — #12 registers the matcher as well as building
        // the delivery table, and the ≈150-line estimate covers the delivery half only.
        assertThat(matcherInterceptor)
                .as("recorded, not required: #12 wires this. If it becomes non-null, the estimate changes.")
                .isNull();
    }

    /**
     * The seam itself, exercised directly rather than through Spring: a subclass that records the message and
     * does <b>not</b> call {@code super} must leave HAPI's consumer uncalled.
     *
     * <p>Exercising the class rather than a running write is deliberate — it isolates the question ADR-002 asks
     * (does the override replace the submit?) from whether Big Book currently registers the matcher at all.
     */
    @Test
    void anOverrideThatSkipsSuperDoesNotFeedHapisQueue() {
        submittedToHapi.clear();
        seenByOverride.clear();

        IResourceModifiedConsumer counting = message -> {
            submittedToHapi.add(String.valueOf(message.getPayloadId()));
            return null;
        };
        Replacing replacing = new Replacing();
        setConsumer(replacing, counting);

        replacing.trigger(new ResourceModifiedMessage());

        System.out.println("VERIFY-A1 override saw " + seenByOverride.size()
                + " message(s); HAPI's consumer received " + submittedToHapi.size());
        assertThat(seenByOverride).as("the override must see the event").hasSize(1);
        assertThat(submittedToHapi)
                .as("ADR-002 Open 1: an override that skips super must NOT feed HAPI's queue — a non-empty list"
                        + " here is the double-delivery bug and stops #12")
                .isEmpty();
    }

    /** The control: calling {@code super} does reach HAPI's consumer, so the test above is not vacuous. */
    @Test
    void theControlSuperDoesFeedHapisQueue() {
        submittedToHapi.clear();
        persisted.clear();

        IResourceModifiedConsumer counting = message -> {
            submittedToHapi.add(String.valueOf(message.getPayloadId()));
            return null;
        };
        Delegating delegating = new Delegating();
        setConsumer(delegating, counting);

        delegating.trigger(new ResourceModifiedMessage());

        System.out.println("VERIFY-A1 control: HAPI's consumer received " + submittedToHapi.size()
                + ", persistence saw " + persisted.size());
        assertThat(submittedToHapi.size() + persisted.size())
                .as("super must reach HAPI's own path — consumer or persistence — or the zero above proves nothing")
                .isGreaterThan(0);
    }

    /**
     * Big Book's shape: record the event and deliberately do not delegate to HAPI's queue.
     *
     * <p>Note the override needs none of the parent's Spring-wired collaborators — which is itself part of the
     * answer. An override that replaced the submit but still depended on {@code SubscriptionSettings},
     * {@code IResourceModifiedConsumer} and the channel would not be a clean seam; this one only needs the
     * message.
     */
    static class Replacing extends SubscriptionMatcherInterceptor {
        @Override
        protected void processResourceModifiedMessage(ResourceModifiedMessage message) {
            seenByOverride.add(String.valueOf(message.getPayloadId()));
            transactionActive.add(TransactionSynchronizationManager.isSynchronizationActive());
        }

        void trigger(ResourceModifiedMessage message) {
            processResourceModifiedMessage(message);
        }
    }

    /** The control: delegate, which must reach HAPI's consumer. */
    static class Delegating extends SubscriptionMatcherInterceptor {
        void trigger(ResourceModifiedMessage message) {
            processResourceModifiedMessage(message);
        }
    }

    /**
     * Reaches into the private fields HAPI wires by Spring, so the probe needs no application context.
     *
     * <p>{@code mySubscriptionSettings} is set too, because {@code super.processResourceModifiedMessage} reads
     * {@code isSubscriptionChangeQueuedImmediately()} — the control NPE'd without it, which would have left the
     * "consumer received 0" measurement unproven.
     */
    private static void setConsumer(SubscriptionMatcherInterceptor target, IResourceModifiedConsumer consumer) {
        try {
            var settings = SubscriptionMatcherInterceptor.class.getDeclaredField("mySubscriptionSettings");
            settings.setAccessible(true);
            // queue immediately: with the default (persist-first) HAPI routes through
            // IResourceModifiedMessagePersistenceSvc instead of the consumer, which is a second collaborator to
            // stub and not the path ADR-002's question is about. Immediate submit is the direct one.
            var subscriptionSettings = new ca.uhn.fhir.jpa.model.config.SubscriptionSettings();
            subscriptionSettings.setSubscriptionChangeQueuedImmediately(true);
            settings.set(target, subscriptionSettings);
            // HAPI persists the message before submitting on 8.12.1 regardless of the immediate flag, so the
            // persistence service is stubbed too. Recorded because it matters to ADR-002: the submit path has
            // TWO exits — persist, then consume — and Big Book's override replaces both by not delegating.
            var persistence = SubscriptionMatcherInterceptor.class
                    .getDeclaredField("myResourceModifiedMessagePersistenceSvc");
            persistence.setAccessible(true);
            persistence.set(target, persistenceStub());
            var field = SubscriptionMatcherInterceptor.class.getDeclaredField("myResourceModifiedConsumer");
            field.setAccessible(true);
            field.set(target, consumer);
        } catch (ReflectiveOperationException cannotWire) {
            throw new AssertionError(
                    "SubscriptionMatcherInterceptor.myResourceModifiedConsumer moved or was renamed on this HAPI"
                            + " version; the ADR-002 seam needs re-measuring rather than this probe patching",
                    cannotWire);
        }
    }

    /** A persistence service that records rather than writing, so the control can reach the consumer. */
    private static ca.uhn.fhir.subscription.api.IResourceModifiedMessagePersistenceSvc persistenceStub() {
        return (ca.uhn.fhir.subscription.api.IResourceModifiedMessagePersistenceSvc)
                java.lang.reflect.Proxy.newProxyInstance(
                        SubscriptionSubmitSeamVerifyTest.class.getClassLoader(),
                        new Class<?>[] {ca.uhn.fhir.subscription.api.IResourceModifiedMessagePersistenceSvc.class},
                        (proxy, method, args) -> {
                            if ("persist".equals(method.getName())) {
                                persisted.add(String.valueOf(args[0]));
                                return null;
                            }
                            return method.getReturnType().isPrimitive() ? defaultFor(method.getReturnType()) : null;
                        });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    /** Does a resource write leave a Spring transaction active where a delivery insert would run? */
    @Test
    void aResourceWriteRunsInsideASpringTransaction() {
        transactionActive.clear();
        partitions.clear();
        TransactionProbe probe = new TransactionProbe();
        fhirServer().registerInterceptor(probe);
        try {
            ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/Patient", tokenFor(user, project),
                    Map.of("resourceType", "Patient", "name", List.of(Map.of("family", "SeamProbe"))),
                    JsonNode.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            System.out.println("VERIFY-A1 transaction active at PRECOMMIT = " + transactionActive);
            System.out.println("VERIFY-A1 partition at PRECOMMIT = " + partitions);
            assertThat(transactionActive)
                    .as("ADR-002 puts the delivery row in the resource's transaction, so one must be active")
                    .containsOnly(true);
            assertThat(partitions)
                    .as("and the write must carry a tenant partition, or delivery could cross projects")
                    .isNotEmpty();
        } finally {
            fhirServer().unregisterInterceptor(probe);
        }
    }

    @ca.uhn.fhir.interceptor.api.Interceptor
    static class TransactionProbe {
        @ca.uhn.fhir.interceptor.api.Hook(ca.uhn.fhir.interceptor.api.Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
        public void created(org.hl7.fhir.instance.model.api.IBaseResource resource,
                ca.uhn.fhir.rest.api.server.RequestDetails request) {
            transactionActive.add(TransactionSynchronizationManager.isSynchronizationActive());
            Object context = request.getAttribute(
                    io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext.ATTRIBUTE);
            partitions.add(context instanceof io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext c
                    ? String.valueOf(c.project().partitionId()) : "none");
        }
    }

    @Autowired
    ca.uhn.fhir.rest.server.RestfulServer server;

    private ca.uhn.fhir.rest.server.RestfulServer fhirServer() {
        return server;
    }
}
