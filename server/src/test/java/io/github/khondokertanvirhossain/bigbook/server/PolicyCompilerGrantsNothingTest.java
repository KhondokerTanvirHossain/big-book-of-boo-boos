package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * #7's second guarantee on the V5 fail-open forms: the write-time refusal in {@link
 * io.github.khondokertanvirhossain.bigbook.server.policy.AccessPolicyProvider} is <b>not</b> the only thing
 * between {@code Observation?status=} and a caller reading every Observation. A policy that reaches the
 * compiler anyway — written before the check existed, inserted directly, or past a bug in the provider — must
 * compile to {@link Criteria.Never} and grant nothing.
 *
 * <p>Ideally a {@code core} unit test, since compilation is a pure function and that is what the seam is for.
 * It lives here because {@code MatchUrlService} needs HAPI's {@code ISearchParamRegistry}, and a hand-rolled
 * registry would test the fake rather than what runs in production. The compiler itself is still touched
 * directly, with no request, no interceptor and no HTTP.
 */
class PolicyCompilerGrantsNothingTest extends LiteStackTest {

    @Autowired
    PolicyCompiler compiler;

    @Test
    void anEmptyParameterValueCompilesToNever() {
        Criteria criteria = compile("Observation?status=").criteriaFor("Observation", Interaction.SEARCH);

        assertThat(criteria).isInstanceOf(Criteria.Never.class);
        assertThat(criteria.allowsEverything())
                .as("the form that matches every Observation inside HAPI must grant nothing here")
                .isFalse();
        assertThat(criteria.deniesEverything()).isTrue();
    }

    @Test
    void notInCompilesToNeverRatherThanMatchingEverything() {
        Criteria criteria = compile("Observation?code:not-in=http://example.org/vs")
                .criteriaFor("Observation", Interaction.SEARCH);

        assertThat(criteria).isInstanceOf(Criteria.Never.class);
    }

    @Test
    void aCriterionThatIsNotASearchUrlAtAllCompilesToNever() {
        Criteria criteria = compile("this is not a search url").criteriaFor("Observation", Interaction.SEARCH);

        assertThat(criteria).isInstanceOf(Criteria.Never.class);
    }

    /** The control: a criterion in the evaluable subset must compile to a Match, or the above prove nothing. */
    @Test
    void aWritableCriterionCompilesToAMatch() {
        Criteria criteria = compile("Observation?status=final").criteriaFor("Observation", Interaction.SEARCH);

        assertThat(criteria).isInstanceOf(Criteria.Match.class);
    }

    private CompiledPolicy compile(String criteria) {
        return compiler.compile(
                List.of(PolicyCompiler.document("AccessPolicy/probe",
                        List.of(Map.of("resourceType", "Observation", "criteria", criteria)))),
                PolicyParameters.forMembership("Practitioner/x", Map.of()),
                false);
    }
}
