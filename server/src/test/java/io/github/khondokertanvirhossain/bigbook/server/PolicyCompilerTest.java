package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaValidator;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The translator (ADR-001). Needs HAPI's `MatchUrlService` and matcher, so it runs against the stack — but
 * the class under test sees no request and no database, which the ArchUnit rules enforce separately.
 */
class PolicyCompilerTest extends LiteStackTest {

    @Autowired FhirContext fhirContext;
    @Autowired InMemoryResourceMatcher matcher;
    @Autowired MatchUrlService matchUrlService;

    private PolicyCompiler compiler() {
        return new PolicyCompiler(new CriteriaValidator(fhirContext, matcher), matchUrlService);
    }

    private static PolicyParameters forPractitioner() {
        return PolicyParameters.forMembership("Practitioner/abc", Map.of());
    }

    @Test
    void aCriterionIsCompiledIntoSomethingTheSearchPathCanUse() {
        CompiledPolicy compiled = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/1",
                List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final")))),
                forPractitioner(), false);

        Criteria criteria = compiled.criteriaFor("Observation", Interaction.READ);
        assertThat(criteria).isInstanceOf(Criteria.Match.class);
        assertThat(((Criteria.Match) criteria).parameters().toNormalizedQueryString(fhirContext)).contains("status=final");
        assertThat(criteria.deniesEverything()).isFalse();
        assertThat(compiled.criteriaFor("Patient", Interaction.READ).deniesEverything())
                .as("a type the policy does not name is denied")
                .isTrue();
    }

    @Test
    void parametersAreSubstitutedAndIdOnlyFormResolvesFirst() {
        CompiledPolicy compiled = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/1",
                List.of(Map.of("resourceType", "Observation", "criteria", "Observation?subject=%patient")))),
                forPractitioner(), false);

        Criteria criteria = compiled.criteriaFor("Observation", Interaction.READ);
        assertThat(criteria).isInstanceOf(Criteria.Match.class);
        assertThat(((Criteria.Match) criteria).source()).isEqualTo("Observation?subject=Practitioner/abc");

        // %patient.id must resolve to the bare id, not "Practitioner/abc.id"
        CompiledPolicy byId = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/2",
                List.of(Map.of("resourceType", "Observation", "criteria", "Observation?_id=%patient.id")))),
                forPractitioner(), false);
        assertThat(((Criteria.Match) byId.criteriaFor("Observation", Interaction.READ)).source())
                .isEqualTo("Observation?_id=abc");
    }

    /** Every failure path must produce Never. This is D14 as a test. */
    @Test
    void everyFailureCompilesToNeverNotAlways() {
        record Case(String name, String criteria, PolicyParameters parameters) {}
        List<Case> cases = List.of(
                new Case("fail-open empty value (V5a)", "Observation?status=", forPractitioner()),
                new Case("in/not-in (V5b)", "Observation?code:in=http://x/ValueSet/y", forPractitioner()),
                new Case(":missing (V5c)", "Observation?subject:missing=false", forPractitioner()),
                new Case("chained", "Observation?subject.name=x", forPractitioner()),
                new Case("_filter", "Patient?_filter=family eq x", forPractitioner()),
                new Case("unknown parameter", "Observation?nope=1", forPractitioner()),
                new Case("unresolvable %requestor (T3: no such parameter)", "Observation?subject=%requestor", forPractitioner()),
                new Case("%patient with no profile on the membership", "Observation?subject=%patient",
                        PolicyParameters.forMembership(null, Map.of())));

        for (Case each : cases) {
            CompiledPolicy compiled = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/x",
                    List.of(Map.of("resourceType", "Observation", "criteria", each.criteria())))),
                    each.parameters(), false);
            Criteria criteria = compiled.criteriaFor("Observation", Interaction.READ);
            assertThat(criteria.deniesEverything()).as(each.name()).isTrue();
            assertThat(criteria.allowsEverything()).as(each.name()).isFalse();
        }
    }

    @Test
    void noPolicyAtAllIsFullProjectAccessAndReadonlyDropsWrites() {
        assertThat(compiler().compile(List.of(), forPractitioner(), false)
                .criteriaFor("Anything", Interaction.DELETE).allowsEverything())
                .as("a membership with no policy sees its whole project (T1)")
                .isTrue();

        CompiledPolicy readonly = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/1",
                List.of(Map.of("resourceType", "Observation", "readonly", true)))), forPractitioner(), false);
        assertThat(readonly.applicable("Observation", Interaction.READ)).hasSize(1);
        assertThat(readonly.applicable("Observation", Interaction.UPDATE)).isEmpty();
        assertThat(readonly.criteriaFor("Observation", Interaction.UPDATE).deniesEverything()).isTrue();
    }

    /** T1: admin does not bypass, and a wildcard never reaches the project-admin types. */
    @Test
    void adminGetsInjectedRulesAndAWildcardNeverCoversProjectAdminTypes() {
        CompiledPolicy admin = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/1",
                List.of(Map.of("resourceType", "*", "criteria", "")))), forPractitioner(), true);

        assertThat(admin.bypass()).as("admin is not a bypass — only Project.superAdmin is").isFalse();
        // the wildcard gives Observation, and the injected rules give Project — read but not delete
        assertThat(admin.criteriaFor("Observation", Interaction.DELETE).allowsEverything()).isTrue();
        assertThat(admin.applicable("Project", Interaction.READ)).isNotEmpty();
        assertThat(admin.applicable("Project", Interaction.DELETE)).isEmpty();
        assertThat(admin.hiddenFieldsFor("Project")).contains("superAdmin", "systemSecret", "strictMode");
        assertThat(admin.hiddenFieldsFor("User")).contains("passwordHash", "mfaSecret");
        assertThat(admin.readonlyFieldsFor("ProjectMembership")).contains("project", "user");

        // a non-admin with a wildcard policy gets nothing on the project-admin types
        CompiledPolicy member = compiler().compile(List.of(PolicyCompiler.document("AccessPolicy/1",
                List.of(Map.of("resourceType", "*", "criteria", "")))), forPractitioner(), false);
        assertThat(member.hiddenFieldsFor("Project")).isEmpty();
    }

    @Test
    void policiesOrTogether() {
        CompiledPolicy compiled = compiler().compile(List.of(
                PolicyCompiler.document("AccessPolicy/1", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))),
                PolicyCompiler.document("AccessPolicy/2", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=amended")))),
                forPractitioner(), false);

        assertThat(compiled.criteriaFor("Observation", Interaction.READ)).isInstanceOf(Criteria.AnyOf.class);
        assertThat(compiled.policyIds()).containsExactly("AccessPolicy/1", "AccessPolicy/2");

        // one good, one refused: the refused one drops out rather than poisoning the other (T2)
        CompiledPolicy mixed = compiler().compile(List.of(
                PolicyCompiler.document("AccessPolicy/1", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=final"))),
                PolicyCompiler.document("AccessPolicy/2", List.of(Map.of("resourceType", "Observation", "criteria", "Observation?status=")))),
                forPractitioner(), false);
        assertThat(mixed.criteriaFor("Observation", Interaction.READ)).isInstanceOf(Criteria.Match.class);
    }
}
