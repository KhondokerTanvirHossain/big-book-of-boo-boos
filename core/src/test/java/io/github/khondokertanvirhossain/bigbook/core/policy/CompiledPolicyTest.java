package io.github.khondokertanvirhossain.bigbook.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `core/policy` is a pure function, so this needs no stack — which is the point of the seam (#7).
 *
 * <p>Includes #7's acceptance criterion: a {@code CompiledPolicy} built <em>directly</em> with the
 * fail-open criterion grants nothing, so the guarantee does not depend on the write-time check having run.
 */
class CompiledPolicyTest {

    @Test
    void aPolicyBuiltDirectlyWithAFailOpenCriterionGrantsNothing() {
        // as if `Observation?status=` had slipped past the validator: the compiler's answer is Never
        Criteria refused = new Criteria.Never("empty parameter value: status");
        CompiledPolicy policy = new CompiledPolicy(false,
                List.of(new CompiledPolicy.Entry("Observation", refused, Set.of(Interaction.values()),
                        List.of(), List.of(), null)),
                List.of("AccessPolicy/1"));

        assertThat(policy.criteriaFor("Observation", Interaction.READ).deniesEverything())
                .as("a Never criterion grants nothing")
                .isTrue();
        assertThat(policy.criteriaFor("Observation", Interaction.READ).allowsEverything()).isFalse();
        assertThat(policy.bypass()).isFalse();
    }

    @Test
    void anEntryThatCoversNoInteractionDeniesRatherThanAllows() {
        CompiledPolicy policy = new CompiledPolicy(false,
                List.of(new CompiledPolicy.Entry("Observation", new Criteria.Always(), Set.of(Interaction.READ),
                        List.of(), List.of(), null)),
                List.of());

        assertThat(policy.applicable("Observation", Interaction.UPDATE)).isEmpty();
        assertThat(policy.criteriaFor("Observation", Interaction.UPDATE).deniesEverything())
                .as("no applicable entry must deny, not allow")
                .isTrue();
        assertThat(policy.criteriaFor("Patient", Interaction.READ).deniesEverything())
                .as("a type no entry covers must deny")
                .isTrue();
    }

    @Test
    void theConstructorCannotProduceAHalfCompiledEntry() {
        // null criteria would be "not compiled yet"; the record refuses to represent it
        CompiledPolicy.Entry entry = new CompiledPolicy.Entry("Observation", null, null, null, null, null);
        CompiledPolicy.Entry untyped = new CompiledPolicy.Entry(null, null, null, null, null, null);

        assertThat(entry.criteria().deniesEverything()).as("null criteria becomes Never, not Always").isTrue();
        assertThat(entry.interactions()).containsExactlyInAnyOrder(Interaction.values());
        assertThat(entry.hiddenFields()).isEmpty();
        assertThat(entry.resourceType()).isEqualTo("Observation");
        assertThat(untyped.resourceType()).as("a null type is the wildcard, never null").isEqualTo("*");
    }

    @Test
    void policiesOrTogetherAndTheIdentitiesFold() {
        Criteria always = new Criteria.Always();
        Criteria never = new Criteria.Never("refused");
        Criteria match = new Criteria.Match("Observation", new ca.uhn.fhir.jpa.searchparam.SearchParameterMap(), "status=final");

        // T2: entries OR. Always absorbs; Never drops out; one survivor is returned bare.
        assertThat(Criteria.anyOf(List.of(match, always)).allowsEverything()).isTrue();
        assertThat(Criteria.anyOf(List.of(match, never))).isEqualTo(match);
        assertThat(Criteria.anyOf(List.of(never, never)).deniesEverything()).isTrue();
        assertThat(Criteria.anyOf(List.of())).isInstanceOf(Criteria.Never.class);
        assertThat(Criteria.anyOf(List.of(match, match))).isInstanceOf(Criteria.AnyOf.class);
    }

    @Test
    void theThreeNamedStartingPoints() {
        assertThat(PolicyDefaults.superAdmin().criteriaFor("Anything", Interaction.DELETE).allowsEverything()).isTrue();
        assertThat(PolicyDefaults.superAdmin().hiddenFieldsFor("Patient")).isEmpty();
        assertThat(PolicyDefaults.fullProjectAccess().criteriaFor("Patient", Interaction.CREATE).allowsEverything()).isTrue();
        assertThat(PolicyDefaults.denyAll("unreadable policy").criteriaFor("Patient", Interaction.READ).deniesEverything()).isTrue();
    }

    @Test
    void hiddenAndReadonlyFieldsAreCollectedPerType() {
        CompiledPolicy policy = new CompiledPolicy(false, List.of(
                new CompiledPolicy.Entry("Observation", new Criteria.Always(), Set.of(Interaction.READ),
                        List.of("note"), List.of(), null),
                new CompiledPolicy.Entry("*", new Criteria.Always(), Set.of(Interaction.values()),
                        List.of("meta.source"), List.of("subject"), null)),
                List.of());

        assertThat(policy.hiddenFieldsFor("Observation")).containsExactlyInAnyOrder("note", "meta.source");
        assertThat(policy.readonlyFieldsFor("Observation")).containsExactly("subject");
        assertThat(policy.hiddenFieldsFor("Patient")).containsExactly("meta.source");
    }
}
