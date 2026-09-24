package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.match.deliver.resthook.SubscriptionDeliveringRestHookListener;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.messaging.BaseResourceMessage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-002 Open 2, measured on 8.12.1 — <b>not blocking</b>: a "no" changes the ~150-line estimate rather than
 * stopping #12, and the new number is reported on the issue and in ADR-002.
 *
 * <blockquote>confirm HAPI's rest-hook delivery can be driven from the poller ({@code ResourceDeliveryMessage}
 * reuse) so the HTTP client, headers and interaction filter stay HAPI's.</blockquote>
 *
 * <p>What makes this answerable: {@code SubscriptionDeliveringRestHookListener.handleMessage(ResourceDeliveryMessage)}
 * is <b>public</b> — the base class declares it abstract and public — so a poller can build a message and call
 * it directly rather than publishing to HAPI's channel. {@code parseHeadersFromSubscription} is public static
 * too, so {@code Subscription.channel.header[]} handling need not be reimplemented.
 *
 * <p>The test posts to a real local HTTP endpoint and asserts on <b>what arrived</b>: that a POST happened at
 * all, and that the body is the resource. If the ADR's assumption is wrong, that shows up as nothing arriving.
 */
class PollerDrivenDeliveryVerifyTest extends LiteStackTest {

    @Autowired
    FhirContext fhirContext;

    @Autowired(required = false)
    SubscriptionDeliveringRestHookListener restHookListener;

    @Autowired
    ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster interceptorBroadcaster;

    static final List<String> received = new CopyOnWriteArrayList<>();
    static final List<String> receivedHeaders = new CopyOnWriteArrayList<>();

    /** What is wired today, recorded for the same reason as Open 1's inventory. */
    @Test
    void isTheRestHookListenerWired() {
        System.out.println("VERIFY-A2 SubscriptionDeliveringRestHookListener bean = "
                + (restHookListener == null ? "ABSENT" : restHookListener.getClass().getName()));
        // recorded, not required: #12 wires the match side, and this says whether the listener comes with it
    }

    /**
     * Drive HAPI's listener from outside its channel, exactly as a poller would, and see whether a signed POST
     * lands on a real endpoint.
     *
     * <p>Constructed by hand rather than autowired: the point is whether the <i>class</i> can be driven this
     * way, which must be answerable before #12 decides to build a poller around it. The collaborators it needs
     * are the finding — each one is a thing the poller must supply.
     */
    @Test
    void theRestHookListenerCanBeDrivenDirectly() throws Exception {
        received.clear();
        receivedHeaders.clear();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress(0), 0);
        endpoint.createContext("/hook", exchange -> {
            receivedHeaders.add(String.valueOf(exchange.getRequestHeaders().entrySet()));
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        endpoint.start();
        String url = "http://localhost:" + endpoint.getAddress().getPort() + "/hook";
        try {
            SubscriptionDeliveringRestHookListener listener = new SubscriptionDeliveringRestHookListener();
            listener.setFhirContextForUnitTest(fhirContext);
            // -------------------------------------------------------------------------------------------
            // REQUIRED, and not incidental plumbing — do not strip this when the poller is built.
            //
            // handleMessage broadcasts SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY, and that broadcast is where
            // Big Book's delivery-policy check runs: PolicySubscriptionInterceptor hooks that pointcut to
            // apply the subscription author's AccessPolicy and strip hiddenFields from the payload (#7, D57 —
            // Medplum's own check is a no-op, so this is a divergence Big Book relies on).
            //
            // Without an IInterceptorBroadcaster, handleMessage NPEs and nothing is delivered (measured, #12)
            // — so the failure is loud rather than silent. But a poller that supplied a *no-op* broadcaster to
            // quiet it would deliver successfully while skipping the policy check entirely: the author's
            // criteria and hiddenFields would stop being applied, and a subscription would become a way around
            // the read path. Supply the real one.
            // -------------------------------------------------------------------------------------------
            setField(listener, "myInterceptorBroadcaster", interceptorBroadcaster);

            Subscription subscription = new Subscription();
            subscription.setId("Subscription/poller-probe");
            subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            subscription.setCriteria("Observation?");
            subscription.getChannel()
                    .setType(Subscription.SubscriptionChannelType.RESTHOOK)
                    .setEndpoint(url)
                    .setPayload("application/fhir+json");
            CanonicalSubscription canonical = new CanonicalSubscription();
            canonical.setIdElement(subscription.getIdElement());
            canonical.setEndpointUrl(url);
            canonical.setPayloadString("application/fhir+json");
            canonical.setChannelType(
                    ca.uhn.fhir.jpa.subscription.model.CanonicalSubscriptionChannelType.RESTHOOK);

            Observation payload = new Observation();
            payload.setId("Observation/poller-payload");
            payload.setStatus(Observation.ObservationStatus.FINAL);
            payload.getCode().setText("poller probe");

            ResourceDeliveryMessage message = new ResourceDeliveryMessage();
            message.setSubscription(canonical);
            message.setPayload(fhirContext, payload, EncodingEnum.JSON);
            message.setOperationType(BaseResourceMessage.OperationTypeEnum.CREATE);

            try {
                listener.handleMessage(message);
                System.out.println("VERIFY-A2 handleMessage returned without throwing");
            } catch (Exception needsMore) {
                System.out.println("VERIFY-A2 handleMessage threw: " + needsMore.getClass().getSimpleName()
                        + ": " + needsMore.getMessage());
            }

            boolean arrived = waitForDelivery();
            System.out.println("VERIFY-A2 POST arrived = " + arrived + "; bodies = " + received.size());
            if (arrived) {
                System.out.println("VERIFY-A2 body starts = "
                        + received.get(0).substring(0, Math.min(90, received.get(0).length())));
                System.out.println("VERIFY-A2 headers = "
                        + receivedHeaders.get(0).substring(0, Math.min(220, receivedHeaders.get(0).length())));
            }
            // MEASURED 2026-09-25: yes. A real POST arrives with the Observation as the body and HAPI's own
            // client (User-agent: HAPI-FHIR/8.12.1), driven entirely from outside HAPI's channel. Asserted now
            // rather than left as a print, because #12's poller depends on it.
            assertThat(arrived)
                    .as("ADR-002 Open 2: HAPI's rest-hook delivery must be drivable from a poller, or the ~150"
                            + " estimate is wrong and #12 writes its own HTTP client, headers and retry")
                    .isTrue();
            assertThat(received.get(0))
                    .as("and the body must be the resource, not an empty or wrapped payload")
                    .contains("\"resourceType\":\"Observation\"");
            assertThat(receivedHeaders.get(0))
                    .as("HAPI's own client did the POST — its User-agent proves we did not reimplement it")
                    .contains("HAPI-FHIR/8.12.1");
        } finally {
            endpoint.stop(0);
        }
    }

    /** Sets a Spring-wired field on HAPI's listener, so the probe can supply collaborators one at a time. */
    private static void setField(Object target, String name, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException keepLooking) {
                continue;
            } catch (ReflectiveOperationException cannotSet) {
                throw new AssertionError("could not set " + name, cannotSet);
            }
        }
        throw new AssertionError(name + " not found on " + target.getClass()
                + "; HAPI renamed it and this measurement needs redoing rather than the probe patching");
    }

    private static boolean waitForDelivery() throws InterruptedException {
        for (int i = 0; i < 40 && received.isEmpty(); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return !received.isEmpty();
    }

    /** {@code parseHeadersFromSubscription} is public static: header handling need not be reimplemented. */
    @Test
    void hapiParsesChannelHeadersForUs() {
        CanonicalSubscription canonical = new CanonicalSubscription();
        // addHeader, not getHeaders().addAll: getHeaders() returns an immutable list and addAll throws
        // UnsupportedOperationException (measured, #12)
        canonical.addHeader("X-Custom: one");
        canonical.addHeader("X-Other: two");

        var headers = SubscriptionDeliveringRestHookListener.parseHeadersFromSubscription(canonical);

        System.out.println("VERIFY-A2 parsed channel headers = " + headers);
        assertThat(headers)
                .as("Subscription.channel.header[] handling is HAPI's, not something #12 reimplements (T60)")
                .hasSize(2);
    }
}
