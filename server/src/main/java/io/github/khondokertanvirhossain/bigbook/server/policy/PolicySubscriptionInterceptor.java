package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscription-side enforcement: <b>a subscription must not deliver what its author could not read</b>.
 *
 * <p>Without this, a subscription is a way around the whole read path. A caller whose policy shows them only
 * their own patients writes {@code Subscription?criteria=Observation} and every Observation in the project
 * arrives at their endpoint — never having passed {@code PREACCESS}, because delivery is not a REST read. The
 * enforcement path's other six hooks are all on request threads; this one is not, which is exactly why it has
 * to exist separately.
 *
 * <p>Two hooks, matching ADR-001:
 *
 * <ul>
 *   <li>{@code SUBSCRIPTION_RESOURCE_MATCHED} — the resource is outside the author's criteria, so the
 *       subscription does not fire at all. Returning {@code false} suppresses it.
 *   <li>{@code SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY} — {@code hiddenFields} are stripped from the payload,
 *       so a field the author cannot read over REST does not arrive by webhook either.
 * </ul>
 *
 * <p><b>Fails closed.</b> If the author's membership or policy cannot be resolved, the subscription does not
 * fire. A subscription that silently stops is a bug someone notices; one that delivers what it should not is a
 * leak nobody notices.
 */
@Interceptor
public class PolicySubscriptionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PolicySubscriptionInterceptor.class);

    private final SubscriptionAuthorPolicy authors;
    private final CriteriaEvaluator evaluator;
    private final FhirContext fhirContext;
    private final PolicyDenialLog denialLog;

    public PolicySubscriptionInterceptor(SubscriptionAuthorPolicy authors, CriteriaEvaluator evaluator,
            FhirContext fhirContext, PolicyDenialLog denialLog) {
        this.authors = authors;
        this.evaluator = evaluator;
        this.fhirContext = fhirContext;
        this.denialLog = denialLog;
    }

    /**
     * @return false to suppress the subscription — the matched resource is outside the author's criteria, or
     *     their policy could not be resolved
     */
    @Hook(Pointcut.SUBSCRIPTION_RESOURCE_MATCHED)
    public boolean authorMayReadTheMatchedResource(CanonicalSubscription subscription, ResourceDeliveryMessage delivery) {
        CompiledPolicy policy = authors.policyFor(subscription, delivery);
        if (policy == null) {
            log.warn("subscription {} not fired: its author's policy could not be resolved", subscription.getIdPart());
            return false;
        }
        if (policy.bypass()) {
            return true;
        }
        IBaseResource resource = delivery.getPayload(fhirContext);
        if (resource == null) {
            // an id-only subscription payload carries nothing to check against criteria. The author's type ×
            // interaction permission below is all that can be checked, and the receiver must fetch the resource
            // over REST — where the full read path applies.
            return permitsReading(policy, delivery.getPayloadId(fhirContext) == null
                    ? null : delivery.getPayloadId(fhirContext).getResourceType());
        }
        String type = resource.fhirType();
        if (!permitsReading(policy, type)) {
            denialLog.denied(null, type, resource.getIdElement().getIdPart(), "SUBSCRIPTION", policy,
                    "author may not read this type");
            return false;
        }
        Criteria criteria = policy.criteriaFor(type, Interaction.READ);
        if (criteria.allowsEverything() || evaluator.satisfies(criteria, resource)) {
            return true;
        }
        denialLog.denied(null, type, resource.getIdElement().getIdPart(), "SUBSCRIPTION", policy,
                "matched resource outside the author's criteria");
        return false;
    }

    /**
     * {@code hiddenFields} on the delivered payload. The same removal as {@code PRESHOW}, at the one place a
     * resource leaves the server without passing through it.
     *
     * @return true always — the firing decision was made at {@code SUBSCRIPTION_RESOURCE_MATCHED}; this hook
     *     only redacts. Suppressing here instead would deliver a 0-byte webhook rather than nothing.
     */
    @Hook(Pointcut.SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY)
    public boolean removeHiddenFieldsFromPayload(CanonicalSubscription subscription, ResourceDeliveryMessage delivery) {
        CompiledPolicy policy = authors.policyFor(subscription, delivery);
        if (policy == null || policy.bypass()) {
            return true;
        }
        IBaseResource resource = delivery.getPayload(fhirContext);
        if (resource == null) {
            return true;
        }
        List<String> hidden = policy.hiddenFieldsFor(resource.fhirType());
        if (hidden.isEmpty()) {
            return true;
        }
        var terser = fhirContext.newTerser();
        for (String path : hidden) {
            terser.getValues(resource, resource.fhirType() + "." + path, true, false).forEach(terser::clear);
        }
        // put the redacted resource back: getPayload parses a copy, so clearing fields on it is not enough
        delivery.setPayload(fhirContext, resource, EncodingEnum.JSON);
        return true;
    }

    private static boolean permitsReading(CompiledPolicy policy, String type) {
        return type != null && !policy.applicable(type, Interaction.READ).isEmpty();
    }
}
