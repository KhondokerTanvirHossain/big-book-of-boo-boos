package io.github.khondokertanvirhossain.bigbook.core.policy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether a criterion may be written at all (D15, BB-R-006.1). Called by the {@code AccessPolicy}
 * and {@code Subscription} write paths; a rejection is a 400 naming the parameter, not a silent downgrade.
 *
 * <p>The subset is <b>narrower than what HAPI reports supported</b> (V5, measured on 8.12.1, issue #7).
 * HAPI answers "supported" for two forms that <b>fail open</b>, and an access policy that fails open is
 * worse than one that will not save:
 *
 * <ul>
 *   <li><b>An empty parameter value</b> — {@code Observation?status=} matches <em>every</em> resource of the
 *       type. A typo, or a parameter substitution that resolved to nothing, would silently grant it all.
 *   <li><b>{@code :in} / {@code :not-in}</b> — the ValueSet is not expanded, so {@code :in} matches nothing
 *       and {@code :not-in} matches everything. ADR-001 Open carries the v0.2 lift condition.
 * </ul>
 *
 * <p>Refusals are deliberate divergences from both HAPI and Medplum: `medplum-parity.md` rows V5a–V5c.
 */
public class CriteriaValidator {

    /** Qualifiers the matcher cannot evaluate, plus the two it evaluates wrongly (V5). */
    private static final List<String> REFUSED_QUALIFIERS =
            List.of("exact", "contains", "above", "below", "text", "missing", "in", "not-in");

    /** `_lastUpdated` is refused by the matcher as a "standard parameter"; the rest are not filters. */
    private static final List<String> REFUSED_PARAMETERS = List.of("_lastUpdated", "_has", "_filter");

    private static final Pattern MATCH_URL = Pattern.compile("^([A-Za-z]+)\\?(.*)$");

    private final FhirContext fhirContext;
    private final InMemoryResourceMatcher matcher;

    public CriteriaValidator(FhirContext fhirContext, InMemoryResourceMatcher matcher) {
        this.fhirContext = fhirContext;
        this.matcher = matcher;
    }

    /**
     * @param criteria a full match URL, {@code Type?params}, with parameters already substituted
     * @throws CriteriaRejectedException naming the parameter, when it may not be written
     */
    public void requireWritable(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            throw new CriteriaRejectedException(null, "A criteria string is required.");
        }
        var url = MATCH_URL.matcher(criteria.trim());
        if (!url.matches()) {
            throw new CriteriaRejectedException(null,
                    "'%s' is not a match URL: it must be ResourceType?parameters.".formatted(criteria));
        }
        String resourceType = url.group(1);
        try {
            fhirContext.getResourceDefinition(resourceType);
        } catch (RuntimeException unknownType) {
            throw new CriteriaRejectedException(null, "'" + resourceType + "' is not a FHIR resource type.");
        }

        requireNoFailOpenParameter(url.group(2), criteria);
        requireEvaluableInMemory(criteria);
    }

    /** True when the criterion may be written; for callers that need a boolean rather than an exception. */
    public boolean writable(String criteria) {
        try {
            requireWritable(criteria);
            return true;
        } catch (CriteriaRejectedException rejected) {
            return false;
        }
    }

    /** The V5 ruling: forms HAPI calls supported but evaluates in a way that grants more than it says. */
    private void requireNoFailOpenParameter(String query, String criteria) {
        if (query.isBlank()) {
            throw new CriteriaRejectedException(null,
                    "'%s' has no parameters, so it would match every %s.".formatted(criteria, criteria.split("\\?")[0]));
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            String bare = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
            if (bare.startsWith("_sort") || bare.startsWith("_include") || bare.startsWith("_revinclude")) {
                // not filters: HAPI ignores them when matching, and they cannot widen access
                continue;
            }
            if (value.isBlank()) {
                // V5a: reports supported, matches everything of the type
                throw new CriteriaRejectedException(name,
                        "Parameter '%s' has no value, so '%s' would match every resource of this type. Give it a value, or remove it."
                                .formatted(name, criteria));
            }
            if (name.contains(":")) {
                String qualifier = name.substring(name.indexOf(':') + 1);
                if (REFUSED_QUALIFIERS.contains(qualifier)) {
                    throw new CriteriaRejectedException(name, reasonForQualifier(qualifier, name));
                }
            }
            if (REFUSED_PARAMETERS.contains(bare)) {
                throw new CriteriaRejectedException(name,
                        "Parameter '%s' cannot be evaluated against a single resource, so it cannot be used in a criteria string."
                                .formatted(name));
            }
            if (bare.contains(".")) {
                throw new CriteriaRejectedException(name,
                        "Chained parameters such as '%s' are not supported in a criteria string.".formatted(name));
            }
        }
    }

    private static String reasonForQualifier(String qualifier, String name) {
        if (qualifier.equals("in") || qualifier.equals("not-in")) {
            // V5b: supported, but the ValueSet is not expanded — :in matches nothing, :not-in everything
            return ("Qualifier ':%s' on '%s' is not supported in a criteria string: the ValueSet is not expanded "
                    + "when matching a single resource, so it would not mean what it says.").formatted(qualifier, name);
        }
        return "Qualifier ':%s' on '%s' cannot be evaluated against a single resource.".formatted(qualifier, name);
    }

    /** HAPI's own answer, last: it catches what the rules above do not enumerate. */
    private void requireEvaluableInMemory(String criteria) {
        InMemoryMatchResult result;
        try {
            result = matcher.canBeEvaluatedInMemory(criteria);
        } catch (MatchUrlService.UnrecognizedSearchParameterException unknownParameter) {
            // HAPI-0488: a parameter this resource type does not have
            throw new CriteriaRejectedException(null, unknownParameter.getMessage());
        } catch (InvalidRequestException malformed) {
            // HAPI-1744: _filter and other shapes the match-URL parser refuses outright
            throw new CriteriaRejectedException(null, malformed.getMessage());
        }
        if (!result.supported()) {
            throw new CriteriaRejectedException(null,
                    "This criteria string cannot be evaluated against a single resource: " + result.getUnsupportedReason());
        }
    }
}
