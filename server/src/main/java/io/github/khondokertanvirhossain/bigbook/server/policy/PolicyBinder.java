package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyDefaults;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyParameters;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the caller's policy once per request and puts it on the request, where every other hook reads it.
 *
 * <p>Runs at {@code SERVER_INCOMING_REQUEST_PRE_HANDLED} — after the tenant filter has established who is
 * calling and which project they are in, and before any rule list is built or any resource is fetched. One
 * resolution per request rather than one per hook, so a policy cannot change halfway through a request: a
 * search that narrowed under one policy and dropped under another would be a hole.
 *
 * <p>On the request attribute rather than a thread local, deliberately. HAPI hands the {@link RequestDetails}
 * to every hook, so the policy travels with the request it belongs to; a thread local outlives the request on
 * a pooled thread, and the failure mode of a stale one is a caller acting under someone else's policy.
 *
 * <p><b>Every failure path yields {@link PolicyDefaults#denyAll}</b>, never "no policy" and never full access.
 * An unreadable policy document is a refusal (D14): the alternative is that a typo in a policy resource
 * silently widens access.
 */
@Interceptor
public class PolicyBinder {

    private static final Logger log = LoggerFactory.getLogger(PolicyBinder.class);

    private final PolicyResolver resolver;

    public PolicyBinder(PolicyResolver resolver) {
        this.resolver = resolver;
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void bind(RequestDetails request) {
        if (request instanceof SystemRequestDetails) {
            // HAPI's own reads carry no caller; the rule builders skip them explicitly rather than relying on
            // the absence of an attribute, so nothing is set here
            return;
        }
        PolicyContext.set(request, resolve(request));
    }

    private CompiledPolicy resolve(RequestDetails request) {
        if (!(request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext caller)) {
            // no tenant context means the request never passed the filter that establishes one
            return PolicyDefaults.denyAll("no project context on request");
        }
        if (caller.superAdmin()) {
            return PolicyDefaults.superAdmin();
        }
        return resolver.compile(caller.membership());
    }

}
