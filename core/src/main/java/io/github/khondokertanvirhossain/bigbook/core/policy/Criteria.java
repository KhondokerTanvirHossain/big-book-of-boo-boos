package io.github.khondokertanvirhossain.bigbook.core.policy;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import java.util.List;

/**
 * A compiled {@code AccessPolicy.criteria}: one of exactly four things (ADR-001).
 *
 * <p>The point of sealing it is {@link Never}. A criterion Big Book will not evaluate — unparseable, or one
 * of the forms that fail open (V5) — compiles to {@code Never}, and there is no representation for
 * "unevaluable, so ignore it". D14's "fails closed" is a type here rather than a convention, so the
 * compiler cannot forget it and a reviewer cannot miss it.
 */
public sealed interface Criteria {

    /** No restriction: the policy entry covers the whole resource type. */
    record Always() implements Criteria {}

    /**
     * Nothing matches. What a refused or unparseable criterion becomes, so it grants nothing rather than
     * everything. {@code AnyOf} treats it as absent, because OR-ing with "nothing" is a no-op.
     */
    record Never(String because) implements Criteria {}

    /** A FHIR search the matcher can evaluate in memory and the search builder can narrow with. */
    record Match(String resourceType, SearchParameterMap parameters, String source) implements Criteria {}

    /** Policies OR together (T2): a resource is visible if any entry's criteria match. */
    record AnyOf(List<Criteria> alternatives) implements Criteria {}

    /** True when this criterion can never match, so callers can skip work without inspecting the type. */
    default boolean deniesEverything() {
        return switch (this) {
            case Never ignored -> true;
            case AnyOf anyOf -> anyOf.alternatives().stream().allMatch(Criteria::deniesEverything);
            default -> false;
        };
    }

    /** True when this criterion restricts nothing, so a search needs no narrowing. */
    default boolean allowsEverything() {
        return switch (this) {
            case Always ignored -> true;
            case AnyOf anyOf -> anyOf.alternatives().stream().anyMatch(Criteria::allowsEverything);
            default -> false;
        };
    }

    /** OR, with the identities folded: {@code Always} absorbs, {@code Never} drops out. */
    static Criteria anyOf(List<Criteria> alternatives) {
        if (alternatives.stream().anyMatch(Criteria::allowsEverything)) {
            return new Always();
        }
        List<Criteria> live = alternatives.stream().filter(each -> !each.deniesEverything()).toList();
        return switch (live.size()) {
            case 0 -> new Never("every alternative denies everything");
            case 1 -> live.get(0);
            default -> new AnyOf(live);
        };
    }
}
