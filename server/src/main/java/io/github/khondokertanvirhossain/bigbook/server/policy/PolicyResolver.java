package io.github.khondokertanvirhossain.bigbook.server.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyDefaults;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyParameters;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Membership \u2192 {@link CompiledPolicy}. The one place that turns a seat into what it may do.
 *
 * <p>Extracted from {@link PolicyBinder} because the request path is no longer the only caller: subscription
 * delivery runs off a request thread and resolves the <i>author's</i> policy through
 * {@link SubscriptionAuthorPolicy}. Two implementations of "what does this membership get" would be two things
 * to keep in step, and the way they drift is that a restriction applied over REST is not applied on delivery.
 *
 * <p>Every failure is a refusal, never full access (D14).
 */
public class PolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(PolicyResolver.class);

    private final PolicyCompiler compiler;
    private final AccessPolicyStore policies;
    private final CompiledPolicyCache cache;
    private final ObjectMapper json;

    public PolicyResolver(PolicyCompiler compiler, AccessPolicyStore policies, CompiledPolicyCache cache,
            ObjectMapper json) {
        this.compiler = compiler;
        this.policies = policies;
        this.cache = cache;
        this.json = json;
    }

    /** @return the membership's compiled policy; {@code denyAll} if it is inactive or will not compile */
    public CompiledPolicy compile(Membership membership) {
        if (membership == null || !membership.active()) {
            return PolicyDefaults.denyAll("no active membership");
        }
        try {
            return compileAttachments(membership);
        } catch (RuntimeException unreadable) {
            // fail closed (D14). The only exit from this handler is denyAll \u2014 see the fail-closed standard
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
    private CompiledPolicy compileAttachments(Membership membership) {
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
        AccessPolicyStore.Loaded loaded = policies.load(membership.projectId(), references);
        PolicyParameters substitutions = PolicyParameters.forMembership(membership.profile(), parameters);
        // the documents are loaded on every request, but compiling them is the expensive part; the cache keys
        // on the policy versions just read, so an edited policy recompiles rather than being served stale
        return cache.get(membership, loaded.versions(),
                () -> compiler.compile(loaded.documents(), substitutions, membership.admin()));
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
