package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import ca.uhn.fhir.rest.api.server.IPreResourceAccessDetails;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyDenialLog;
import io.github.khondokertanvirhossain.bigbook.server.policy.PolicyTestAccess;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * ADR-001's paging rule says a {@code PREACCESS} drop on a primary-query entry is a <b>narrowing defect</b>:
 * the caller is still safe, but the pre-query narrowing failed and that is a bug.
 *
 * <p>That path only runs when something has gone wrong, so it would never be exercised by a passing system —
 * which means it would not be tested at all. Here the narrowing is <b>forced to miss</b>: the interceptor is
 * handed a resource that does not satisfy its criteria, as if the query had returned it. The drop must remove
 * it and the WARN must fire.
 */
class NarrowingBackstopTest extends LiteStackTest {

    @Autowired
    InMemoryResourceMatcher matcher;

    @Autowired
    FhirContext fhirContext;

    @Test
    void aPrimaryQueryResultThatFailsCriteriaIsDroppedAndLoggedAsADefect() {
        PolicyDenialLog log = new PolicyDenialLog();
        var interceptor = PolicyTestAccess.enforcement(matcher, log);
        // a policy that only permits final Observations
        CompiledPolicy policy = new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Observation",
                new Criteria.Match("Observation", new SearchParameterMap(), "Observation?status=final"),
                Set.of(Interaction.values()), List.of(), List.of(), null)), List.of("AccessPolicy/1"));

        // …and a resource the narrowing should have excluded but did not
        Observation shouldHaveBeenExcluded = new Observation();
        shouldHaveBeenExcluded.setId("Observation/leaked");
        shouldHaveBeenExcluded.setStatus(Observation.ObservationStatus.CANCELLED);
        Observation permitted = new Observation();
        permitted.setId("Observation/allowed");
        permitted.setStatus(Observation.ObservationStatus.FINAL);

        RequestDetails request = primarySearch(policy);
        RecordingAccessDetails details = new RecordingAccessDetails(List.of(shouldHaveBeenExcluded, permitted));

        interceptor.dropWhatFailsCriteria(request, details);

        assertThat(details.dropped).as("the drop must remove what the narrowing missed").containsExactly(0);
        assertThat(log.narrowingMissedCount())
                .as("and the WARN must fire: a primary-query drop is a narrowing defect, not routine filtering")
                .isEqualTo(1);
    }

    /** The same drop on an include is routine, not a defect: no WARN. */
    @Test
    void anIncludedResourceThatFailsCriteriaIsDroppedWithoutTheDefectWarning() {
        PolicyDenialLog log = new PolicyDenialLog();
        var interceptor = PolicyTestAccess.enforcement(matcher, log);
        CompiledPolicy policy = new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Observation",
                new Criteria.Match("Observation", new SearchParameterMap(), "Observation?status=final"),
                Set.of(Interaction.values()), List.of(), List.of(), null)), List.of("AccessPolicy/1"));
        Observation included = new Observation();
        included.setId("Observation/included");
        included.setStatus(Observation.ObservationStatus.CANCELLED);

        // an $everything operation: the resource did not come from the primary query
        RequestDetails request = primarySearch(policy);
        request.setRestOperationType(RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);
        RecordingAccessDetails details = new RecordingAccessDetails(List.of(included));

        interceptor.dropWhatFailsCriteria(request, details);

        assertThat(details.dropped).as("still dropped — the caller must not see it").containsExactly(0);
        assertThat(log.narrowingMissedCount())
                .as("but not a narrowing defect: an include was never part of the narrowed query")
                .isZero();
    }

    /** A Never criterion drops everything, and that is not a narrowing defect either. */
    @Test
    void aDeniedTypeDropsEverythingWithoutClaimingADefect() {
        PolicyDenialLog log = new PolicyDenialLog();
        var interceptor = PolicyTestAccess.enforcement(matcher, log);
        CompiledPolicy policy = new CompiledPolicy(false, List.of(new CompiledPolicy.Entry(
                "Observation", new Criteria.Never("refused at write time"),
                Set.of(Interaction.values()), List.of(), List.of(), null)), List.of("AccessPolicy/1"));
        Observation any = new Observation();
        any.setId("Observation/any");
        any.setStatus(Observation.ObservationStatus.FINAL);

        RecordingAccessDetails details = new RecordingAccessDetails(List.of(any));
        interceptor.dropWhatFailsCriteria(primarySearch(policy), details);

        assertThat(details.dropped).containsExactly(0);
        assertThat(log.narrowingMissedCount())
                .as("a Never criterion is not a narrowing miss: there was nothing to narrow to")
                .isZero();
    }

    /**
     * A caller's request, not a {@code SystemRequestDetails}: the interceptor deliberately skips system
     * requests, because HAPI's own internal reads must not be filtered by a caller's policy. Using one here
     * would have made every assertion below pass for the wrong reason.
     */
    private RequestDetails primarySearch(CompiledPolicy policy) {
        ServletRequestDetails request = new ServletRequestDetails() {
            @Override
            public FhirContext getFhirContext() {
                // no RestfulServer behind this request; the context is all the interceptor needs from it
                return fhirContext;
            }
        };
        // ServletRequestDetails.setAttribute delegates to the HttpServletRequest, so PolicyContext needs a
        // real one — the same coupling production has, kept rather than mocked around.
        request.setServletRequest(new MockHttpServletRequest());
        request.setRestOperationType(RestOperationTypeEnum.SEARCH_TYPE);
        request.setResourceName("Observation");
        return PolicyTestAccess.withPolicy(request, policy);
    }

    /** Records which indexes were dropped, which is the behaviour under test. */
    private static final class RecordingAccessDetails implements IPreResourceAccessDetails {
        private final List<org.hl7.fhir.instance.model.api.IBaseResource> resources;
        private final List<Integer> dropped = new java.util.ArrayList<>();

        private RecordingAccessDetails(List<? extends org.hl7.fhir.instance.model.api.IBaseResource> resources) {
            this.resources = List.copyOf(resources);
        }

        @Override
        public int size() {
            return resources.size();
        }

        @Override
        public org.hl7.fhir.instance.model.api.IBaseResource getResource(int index) {
            return resources.get(index);
        }

        @Override
        public void setDontReturnResourceAtIndex(int index) {
            dropped.add(index);
        }

    }
}
