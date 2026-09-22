package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Whether a {@link Criteria} holds for one resource — asked of HAPI's matcher rather than reimplemented.
 *
 * <p>One class, used by <b>both</b> the read drop and the write check, deliberately. Two copies of this
 * decision would be two things to keep in step, and the failure mode of them drifting is that a resource a
 * caller may not read becomes one they may write. There is one answer to "is this resource inside the
 * criteria", so there is one implementation of it.
 */
public class CriteriaEvaluator {

    private final InMemoryResourceMatcher matcher;

    public CriteriaEvaluator(InMemoryResourceMatcher matcher) {
        this.matcher = matcher;
    }

    public boolean satisfies(Criteria criteria, IBaseResource resource) {
        return switch (criteria) {
            case Criteria.Always ignored -> true;
            case Criteria.Never ignored -> false;
            case Criteria.Match match -> {
                InMemoryMatchResult result = matcher.match(match.source(), resource, null, null);
                // an unsupported criterion at request time denies: fails closed (D14). The validator refuses
                // these at write time, so arriving here means one got past that — and the safe answer is no.
                yield result.supported() && result.matched();
            }
            case Criteria.AnyOf anyOf -> anyOf.alternatives().stream().anyMatch(each -> satisfies(each, resource));
        };
    }
}
