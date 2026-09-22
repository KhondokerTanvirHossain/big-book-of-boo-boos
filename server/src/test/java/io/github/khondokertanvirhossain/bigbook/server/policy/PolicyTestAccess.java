package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;

/**
 * The two package-private seams a test needs, exposed deliberately rather than by widening the production
 * API: building the interceptor, and putting a compiled policy on a request.
 *
 * <p>In the <b>test</b> source tree but in this package, so it reaches {@link PolicyContext}'s package-private
 * setter without that seam widening for production code, and without spending production lines on test
 * scaffolding. Making the attribute public instead would let any class anywhere set a request's policy.
 */
public final class PolicyTestAccess {

    private PolicyTestAccess() {
    }

    public static PolicyEnforcementInterceptor enforcement(InMemoryResourceMatcher matcher, PolicyDenialLog log) {
        return new PolicyEnforcementInterceptor(matcher, log);
    }

    public static RequestDetails withPolicy(RequestDetails request, CompiledPolicy policy) {
        PolicyContext.set(request, policy);
        return request;
    }
}
