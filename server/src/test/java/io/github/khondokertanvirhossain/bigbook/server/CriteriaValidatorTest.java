package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaRejectedException;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The V5 ruling (issue #7, 2026-09-22): the evaluable subset, and the two forms refused for failing open. */
class CriteriaValidatorTest extends LiteStackTest {

    @Autowired
    FhirContext fhirContext;

    @Autowired
    InMemoryResourceMatcher matcher;

    private CriteriaValidator validator() {
        return new CriteriaValidator(fhirContext, matcher);
    }

    @Test
    void theEvaluableSubsetIsAccepted() {
        for (String writable : List.of(
                "Observation?status=final",
                "Observation?subject=Patient/123",
                "Observation?subject=123",
                "Patient?family=Rahman",
                "Observation?status:not=cancelled",
                "Observation?value-quantity=5.4|http://unitsofmeasure.org|mg",
                "RiskAssessment?probability=0.8",
                "Observation?date=ge2026-01-01",
                "Patient?_id=123",
                "Patient?_tag=http://x|y",
                "Patient?_profile=http://x/Profile",
                "Patient?_security=http://x|y",
                "Patient?_source=http://x",
                "Observation?status=final&subject=Patient/123",
                "Observation?status=final,amended",
                "Observation?status=final&_sort=-date")) {
            assertThat(validator().writable(writable)).as(writable).isTrue();
        }
    }

    /** V5a: the hazard. HAPI reports these supported and matches everything of the type. */
    @Test
    void anEmptyParameterValueIsRejectedAndNamesTheParameter() {
        assertThatThrownBy(() -> validator().requireWritable("Observation?status="))
                .isInstanceOf(CriteriaRejectedException.class)
                .hasMessageContaining("status")
                .hasMessageContaining("every resource of this type");
        assertThat(((CriteriaRejectedException) org.assertj.core.api.Assertions
                .catchThrowable(() -> validator().requireWritable("Observation?status="))).parameter())
                .isEqualTo("status");

        for (String failsOpen : List.of("Observation?status=", "Observation?subject=", "Observation?code=",
                "Patient?family=", "Observation?status=final&subject=")) {
            assertThat(validator().writable(failsOpen)).as(failsOpen).isFalse();
        }
        // and a criterion with no parameters at all is the same hazard
        assertThat(validator().writable("Observation?")).isFalse();
    }

    /** V5b: supported by HAPI, but the ValueSet is not expanded, so it means the opposite of what it says. */
    @Test
    void inAndNotInAreRejectedWithTheReasonWhy() {
        for (String qualifier : List.of("in", "not-in")) {
            assertThatThrownBy(() -> validator().requireWritable(
                    "Observation?code:" + qualifier + "=http://hl7.org/fhir/ValueSet/observation-codes"))
                    .isInstanceOf(CriteriaRejectedException.class)
                    .hasMessageContaining("ValueSet is not expanded");
        }
    }

    /** V5c: ADR-001 and D15 both said :missing was evaluable. It is not. */
    @Test
    void theOtherQualifiersIncludingMissingAreRejected() {
        for (String criteria : List.of(
                "Observation?subject:missing=false",
                "Patient?family:exact=Rahman",
                "Patient?family:contains=ahm",
                "Observation?code:above=1234-5",
                "Observation?code:below=1234-5",
                "Observation?code:text=glucose")) {
            assertThat(validator().writable(criteria)).as(criteria).isFalse();
        }
    }

    @Test
    void chainingHasFilterAndUnknownParametersAreRejected() {
        for (String criteria : List.of(
                "Observation?subject.name=Rahman",
                "Patient?_has:Observation:subject:status=final",
                "Patient?_filter=family eq Rahman",
                "Patient?_lastUpdated=ge2026-01-01",
                "Observation?no-such-param=x",
                "NotAResourceType?status=final",
                "not a match url",
                "")) {
            assertThat(validator().writable(criteria)).as(criteria).isFalse();
        }
    }
}
