package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import io.github.khondokertanvirhossain.bigbook.server.policy.CriteriaEvaluator;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyDenialLog;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicySubscriptionInterceptor;
import io.github.khondokertanvirhossain.bigbook.server.policy.SubscriptionAuthorPolicy;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Subscription-side enforcement (ADR-001; BB-R-007 D57 — Medplum's own check is a no-op).
 *
 * <p>Delivery is the one path a resource leaves the server without passing {@code PREACCESS}, so a subscription
 * is otherwise a way around the entire read path: a caller restricted to their own patients subscribes to
 * {@code Observation} and receives every Observation in the project.
 *
 * <p>The interceptor is exercised directly rather than through a fired subscription, because <b>nothing records
 * subscription authorship until #12</b>. That is asserted here too: with no author, delivery is suppressed. The
 * criteria and {@code hiddenFields} behaviour is tested against a policy supplied directly, which is the part
 * #7 owns; #12 supplies the author and the end-to-end firing test.
 */
class SubscriptionPolicyEnforcementTest extends LiteStackTest {

    @Autowired
    CriteriaEvaluator evaluator;

    @Autowired
    FhirContext fhirContext;

    @Autowired
    SubscriptionAuthorPolicy realAuthors;

    /** With no recorded author, delivery is suppressed — fail closed, not fail open (#12 supplies the author). */
    @Test
    void aSubscriptionWithNoRecordedAuthorDoesNotFire() {
        var interceptor = new PolicySubscriptionInterceptor(realAuthors, evaluator, fhirContext, new PolicyDenialLog());

        boolean fires = interceptor.authorMayReadTheMatchedResource(subscription(), delivery(finalObservation()));

        assertThat(fires)
                .as("an unresolvable author must suppress delivery: guessing one would be an escalation")
                .isFalse();
    }

    @Test
    void aResourceOutsideTheAuthorsCriteriaDoesNotFire() {
        var interceptor = interceptorWith(policyFor("Observation?status=cancelled"));

        assertThat(interceptor.authorMayReadTheMatchedResource(subscription(), delivery(finalObservation())))
                .as("a final Observation is outside a cancelled-only policy")
                .isFalse();
    }

    @Test
    void aResourceInsideTheAuthorsCriteriaFires() {
        var interceptor = interceptorWith(policyFor("Observation?status=final"));

        assertThat(interceptor.authorMayReadTheMatchedResource(subscription(), delivery(finalObservation())))
                .as("the control: an author who may read the resource must still receive it")
                .isTrue();
    }

    /** A type absent from the author's policy cannot be delivered, whatever the criteria say. */
    @Test
    void aTypeTheAuthorCannotReadDoesNotFire() {
        CompiledPolicy patientsOnly = new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Patient", new Criteria.Always(), Set.of(Interaction.values()), List.of(), List.of(), null)),
                List.of("AccessPolicy/1"));

        assertThat(interceptorWith(patientsOnly).authorMayReadTheMatchedResource(subscription(), delivery(finalObservation())))
                .isFalse();
    }

    /** hiddenFields are removed from the payload, so a webhook is not a way to read a hidden field. */
    @Test
    void hiddenFieldsAreStrippedFromTheDeliveredPayload() {
        CompiledPolicy hidesNote = new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Observation", new Criteria.Always(), Set.of(Interaction.values()),
                List.of("note"), List.of(), null)), List.of("AccessPolicy/1"));
        Observation withNote = finalObservation();
        withNote.addNote().setText("SECRET-CLINICAL-NOTE");
        ResourceDeliveryMessage delivery = delivery(withNote);
        assertThat(delivery.getPayloadString()).as("the baseline: the note is in the payload to begin with")
                .contains("SECRET-CLINICAL-NOTE");

        interceptorWith(hidesNote).removeHiddenFieldsFromPayload(subscription(), delivery);

        assertThat(delivery.getPayloadString())
                .as("a field hidden over REST must not arrive by webhook")
                .doesNotContain("SECRET-CLINICAL-NOTE");
    }

    private PolicySubscriptionInterceptor interceptorWith(CompiledPolicy policy) {
        // a stand-in for #12's author lookup: the policy of a membership that authored the subscription
        SubscriptionAuthorPolicy authors = new SubscriptionAuthorPolicy(null, null, fhirContext) {
            @Override
            public CompiledPolicy policyFor(CanonicalSubscription subscription, ResourceDeliveryMessage delivery) {
                return policy;
            }
        };
        return new PolicySubscriptionInterceptor(authors, evaluator, fhirContext, new PolicyDenialLog());
    }

    private CompiledPolicy policyFor(String criteria) {
        return new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Observation",
                new Criteria.Match("Observation", new ca.uhn.fhir.jpa.searchparam.SearchParameterMap(), criteria),
                Set.of(Interaction.values()), List.of(), List.of(), null)), List.of("AccessPolicy/1"));
    }

    private static Observation finalObservation() {
        Observation observation = new Observation();
        observation.setId("Observation/delivered");
        observation.setStatus(Observation.ObservationStatus.FINAL);
        observation.getCode().setText("subscription probe");
        return observation;
    }

    private ResourceDeliveryMessage delivery(Observation payload) {
        ResourceDeliveryMessage message = new ResourceDeliveryMessage();
        message.setPartitionId(RequestPartitionId.fromPartitionId(1));
        message.setPayload(fhirContext, payload, EncodingEnum.JSON);
        return message;
    }

    private static CanonicalSubscription subscription() {
        CanonicalSubscription subscription = new CanonicalSubscription();
        subscription.setIdElement(new org.hl7.fhir.r4.model.IdType("Subscription", "probe"));
        return subscription;
    }
}
