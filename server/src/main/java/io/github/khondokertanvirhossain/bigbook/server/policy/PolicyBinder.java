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

    private final PolicyCompiler compiler;
    private final AccessPolicyStore policies;
    private final ObjectMapper json;

    public PolicyBinder(PolicyCompiler compiler, AccessPolicyStore policies, ObjectMapper json) {
        this.compiler = compiler;
        this.policies = policies;
        this.json = json;
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
        Membership membership = caller.membership();
        if (membership == null || !membership.active()) {
            return PolicyDefaults.denyAll("no active membership");
        }
        try {
            return compile(membership);
        } catch (RuntimeException unreadable) {
            // fail closed (D14). The only exit from this handler is denyAll — see the fail-closed standard
            // pinned in PolicyArchitectureTest rule 4 before adding another handler like this.
            log.warn("policy for membership {} could not be compiled; denying all access", membership.id(), unreadable);
            return PolicyDefaults.denyAll("policy could not be compiled");
        }
    }

    /**
     * {@code ProjectMembership.accessPolicy} and each {@code access[].policy} are references to
     * {@code AccessPolicy} resources; {@code access[].parameter[]} supplies that attachment's substitutions.
     * They concatenate, and their criteria OR (T3).
     */
    private CompiledPolicy compile(Membership membership) throws RuntimeException {
        List<String> references = new ArrayList<>();
        Map<String, String> parameters = new LinkedHashMap<>();
        if (membership.accessPolicy() != null && !membership.accessPolicy().isBlank()) {
            reference(membership.accessPolicy()).ifPresent(references::add);
        }
        for (JsonNode entry : readAccess(membership.access())) {
            JsonNode policy = entry.path("policy");
            reference(policy.isTextual() ? policy.asText() : policy.toString()).ifPresent(references::add);
            for (JsonNode parameter : entry.path("parameter")) {
                String name = parameter.path("name").asText(null);
                String value = parameter.path("valueReference").path("reference").asText(null);
                if (name != null && value != null) {
                    parameters.put(name, value);
                }
            }
        }
        List<PolicyCompiler.PolicyDocument> documents = policies.load(membership.projectId(), references);
        PolicyParameters substitutions = PolicyParameters.forMembership(membership.profile(), parameters);
        return compiler.compile(documents, substitutions, membership.admin());
    }

    /** {@code access} is stored as the JSON array Medplum sends; absent or malformed means no entries. */
    private Iterable<JsonNode> readAccess(String access) {
        if (access == null || access.isBlank()) {
            return List.of();
        }
        try {
            JsonNode parsed = json.readTree(access);
            return parsed.isArray() ? parsed : List.of();
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            // not readable, so not grantable: the caller gets whatever their other attachments allow, and the
            // malformed one contributes nothing rather than being guessed at
            log.warn("membership access[] is not valid JSON; ignoring it", malformed);
            return List.of();
        }
    }

    /** A {@code Reference} may arrive as {@code {"reference":"AccessPolicy/x"}} or as the bare string. */
    private java.util.Optional<String> reference(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            try {
                text = json.readTree(text).path("reference").asText("");
            } catch (com.fasterxml.jackson.core.JacksonException notJson) {
                return java.util.Optional.empty();
            }
        }
        return text.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(text);
    }
}
