package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;

/**
 * The {@link CompiledPolicy} for the request being handled, resolved once and read by every hook.
 *
 * <p>Kept on the {@link RequestDetails} rather than a thread local: HAPI's own hooks are handed the request,
 * and a compiled policy that could be read for the wrong request is exactly the bug this package must not
 * have.
 */
public final class PolicyContext {

    static final String ATTRIBUTE = "io.github.khondokertanvirhossain.bigbook.server.policy.PolicyContext";

    private PolicyContext() {
    }

    static void set(RequestDetails request, CompiledPolicy policy) {
        request.setAttribute(ATTRIBUTE, policy);
    }

    /**
     * @return the policy for this request, or {@code null} when none was resolved — which for an
     *     unauthenticated or internal request means the hooks must do nothing rather than guess
     */
    static CompiledPolicy of(RequestDetails request) {
        return request == null || !(request.getAttribute(ATTRIBUTE) instanceof CompiledPolicy policy) ? null : policy;
    }
}
