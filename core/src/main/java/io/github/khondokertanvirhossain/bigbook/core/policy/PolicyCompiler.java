package io.github.khondokertanvirhossain.bigbook.core.policy;

import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code AccessPolicy} JSON → {@link CompiledPolicy}, for one membership (ADR-001's translator).
 *
 * <p><b>A pure function of (policies, membership).</b> It may not see a pointcut, a request or the database
 * — enforced by an ArchUnit rule — which is what makes the whole policy engine testable without a stack and
 * what makes ADR-001's "retarget the translator at another engine" a real option rather than a hope.
 *
 * <p>It fails closed everywhere (D14): a criterion it cannot compile becomes {@link Criteria.Never}, never
 * {@code Always}; a policy it cannot read at all becomes {@link PolicyDefaults#denyAll}. There is no path
 * through this class that widens access on error.
 */
public class PolicyCompiler {

    private static final Logger log = LoggerFactory.getLogger(PolicyCompiler.class);

    private final CriteriaValidator validator;
    private final MatchUrlService matchUrlService;

    public PolicyCompiler(CriteriaValidator validator, MatchUrlService matchUrlService) {
        this.validator = validator;
        this.matchUrlService = matchUrlService;
    }

    /**
     * @param policies the {@code AccessPolicy} resources attached to the membership, in attachment order;
     *     empty means full project access (T1)
     * @param parameters this membership's substitutions
     * @param admin the membership's {@code admin} flag — which does not bypass (T1)
     */
    public CompiledPolicy compile(List<PolicyDocument> policies, PolicyParameters parameters, boolean admin) {
        if (policies == null || policies.isEmpty()) {
            return admin ? withAdminRules(PolicyDefaults.fullProjectAccess(), List.of())
                    : PolicyDefaults.fullProjectAccess();
        }
        List<CompiledPolicy.Entry> entries = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (PolicyDocument policy : policies) {
            ids.add(policy.id());
            for (PolicyDocument.ResourceRule rule : policy.resources()) {
                entries.add(compileRule(policy.id(), rule, parameters));
            }
        }
        CompiledPolicy compiled = new CompiledPolicy(false, entries, ids);
        return admin ? withAdminRules(compiled, ids) : compiled;
    }

    /** An admin gets its own policy plus the injected project-admin-type rules (T1). */
    private CompiledPolicy withAdminRules(CompiledPolicy compiled, List<String> ids) {
        List<CompiledPolicy.Entry> entries = new ArrayList<>(compiled.entries());
        entries.addAll(AdminTypeRules.forAdminMembership());
        return new CompiledPolicy(false, entries, ids);
    }

    private CompiledPolicy.Entry compileRule(String policyId, PolicyDocument.ResourceRule rule, PolicyParameters parameters) {
        // T1 (a wildcard never reaches the project-admin types) is enforced by Entry.covers, not here:
        // an invariant on the record cannot be routed around by a caller that builds one by hand.
        String resourceType = rule.resourceType() == null || rule.resourceType().isBlank() ? "*" : rule.resourceType().trim();
        return new CompiledPolicy.Entry(
                resourceType,
                compileCriteria(policyId, resourceType, rule.criteria(), parameters),
                interactions(rule),
                rule.hiddenFields(),
                rule.readonlyFields(),
                rule.compartment());
    }

    /**
     * The one place a criterion becomes something the enforcement path can use — or {@link Criteria.Never}.
     * Every failure lands here on purpose: an unresolved parameter, a refused form, a parse failure.
     */
    private Criteria compileCriteria(String policyId, String resourceType, String criteria, PolicyParameters parameters) {
        if (criteria == null || criteria.isBlank()) {
            // no criteria means "the whole type", which is what Medplum means too
            return new Criteria.Always();
        }
        String substituted;
        try {
            substituted = parameters.substitute(criteria);
        } catch (CriteriaRejectedException unresolved) {
            return never(policyId, criteria, "a parameter could not be resolved: " + unresolved.getMessage());
        }
        try {
            validator.requireWritable(substituted);
        } catch (CriteriaRejectedException refused) {
            return never(policyId, substituted, refused.getMessage());
        }
        try {
            // parseAndTranslateMatchUrl resolves the resource type itself; translateMatchUrl needs it passed in
            MatchUrlService.ResourceTypeAndSearchParameterMap parsed = matchUrlService.parseAndTranslateMatchUrl(substituted);
            return new Criteria.Match(parsed.resourceType(), parsed.searchParameterMap(), substituted);
        } catch (RuntimeException unparseable) {
            return never(policyId, substituted, "could not be parsed: " + unparseable.getMessage());
        }
    }

    /** Fails closed, and says so once per compile rather than silently. */
    private Criteria never(String policyId, String criteria, String because) {
        log.warn("AccessPolicy {} criterion '{}' grants nothing: {}", policyId, criteria, because);
        return new Criteria.Never(because);
    }

    private static Set<Interaction> interactions(PolicyDocument.ResourceRule rule) {
        if (rule.readonly()) {
            return Set.of(Interaction.READ, Interaction.SEARCH, Interaction.HISTORY, Interaction.VREAD);
        }
        if (rule.interactions() == null || rule.interactions().isEmpty()) {
            return Set.of(Interaction.values());
        }
        Set<Interaction> named = new LinkedHashSet<>();
        for (String each : rule.interactions()) {
            // an unknown interaction narrows rather than widens: it is simply not added
            Interaction.of(each).ifPresent(named::add);
        }
        return named.isEmpty() ? Set.of() : named;
    }

    /** The shape of an {@code AccessPolicy} the compiler reads, so `core/policy` needs no FHIR parser. */
    public record PolicyDocument(String id, List<ResourceRule> resources) {

        public PolicyDocument {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        public record ResourceRule(
                String resourceType,
                String criteria,
                List<String> interactions,
                boolean readonly,
                List<String> readonlyFields,
                List<String> hiddenFields,
                String compartment) {

            public ResourceRule {
                interactions = interactions == null ? List.of() : List.copyOf(interactions);
                readonlyFields = readonlyFields == null ? List.of() : List.copyOf(readonlyFields);
                hiddenFields = hiddenFields == null ? List.of() : List.copyOf(hiddenFields);
            }
        }
    }

    /** Convenience for the common case: one policy, read from a map as the provider will hand it over. */
    public static PolicyDocument document(String id, List<Map<String, Object>> resources) {
        List<PolicyDocument.ResourceRule> rules = new ArrayList<>();
        for (Map<String, Object> each : resources) {
            rules.add(new PolicyDocument.ResourceRule(
                    (String) each.get("resourceType"),
                    (String) each.get("criteria"),
                    stringList(each.get("interaction")),
                    Boolean.TRUE.equals(each.get("readonly")),
                    stringList(each.get("readonlyFields")),
                    stringList(each.get("hiddenFields")),
                    (String) each.get("compartment")));
        }
        return new PolicyDocument(id, rules);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
