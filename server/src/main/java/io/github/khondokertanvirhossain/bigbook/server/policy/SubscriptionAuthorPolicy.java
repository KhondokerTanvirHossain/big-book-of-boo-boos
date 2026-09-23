package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Project;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The policy of the membership that <b>authored</b> a subscription, resolved off the delivery thread.
 *
 * <p>Delivery does not run on a request, so there is no {@code RequestDetails} and no {@link PolicyContext} to
 * read: the author has to be recovered from the subscription itself and compiled here. The project comes from
 * the delivery message's partition, which is the same tenancy boundary the REST path uses.
 *
 * <p><b>Authorship is recorded by #12, not here.</b> Nothing in Big Book writes {@code meta.author} yet, so
 * today this resolves to empty for every subscription and delivery is suppressed — which is the fail-closed
 * direction, and honest: the alternative would be to guess an author, and guessing an identity in an
 * authorisation check is how privilege escalation gets written. When #12 records the authoring membership on
 * write, {@link #authorMembershipOf} is the one method that changes.
 *
 * @see PolicySubscriptionInterceptor
 */
public class SubscriptionAuthorPolicy {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAuthorPolicy.class);

    private final TenantStore tenants;
    private final PolicyResolver resolver;
    private final FhirContext fhirContext;

    public SubscriptionAuthorPolicy(TenantStore tenants, PolicyResolver resolver, FhirContext fhirContext) {
        this.tenants = tenants;
        this.resolver = resolver;
        this.fhirContext = fhirContext;
    }

    /** @return the author's compiled policy, or null when it cannot be resolved — which suppresses delivery */
    public CompiledPolicy policyFor(CanonicalSubscription subscription, ResourceDeliveryMessage delivery) {
        Optional<Project> project = projectOf(delivery);
        if (project.isEmpty()) {
            log.warn("subscription {}: no project for partition {}", subscription.getIdPart(), delivery.getPartitionId());
            return null;
        }
        return authorMembershipOf(subscription, project.get())
                .map(resolver::compile)
                .orElse(null);
    }

    /**
     * The membership that authored the subscription.
     *
     * <p>Empty until #12 records it. Medplum keeps it in {@code meta.author}; Big Book will record the authoring
     * {@code ProjectMembership} when the subscription is written, because a reference to a {@code User} is not
     * enough — the same user may hold seats in several projects with different policies, and the policy that
     * applies is the one for the seat that created the subscription.
     */
    private Optional<Membership> authorMembershipOf(CanonicalSubscription subscription, Project project) {
        log.debug("subscription {} in project {} has no recorded author membership (#12): not firing",
                subscription.getIdPart(), project.id());
        return Optional.empty();
    }

    private Optional<Project> projectOf(ResourceDeliveryMessage delivery) {
        var partition = delivery.getPartitionId();
        if (partition == null || partition.getFirstPartitionIdOrNull() == null) {
            return Optional.empty();
        }
        return tenants.projectByPartition(partition.getFirstPartitionIdOrNull());
    }
}
