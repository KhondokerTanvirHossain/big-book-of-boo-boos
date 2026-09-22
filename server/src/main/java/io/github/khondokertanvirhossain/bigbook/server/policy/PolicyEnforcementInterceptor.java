package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.IPreResourceAccessDetails;
import ca.uhn.fhir.rest.api.server.IPreResourceShowDetails;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.util.FhirTerser;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Where a {@link CompiledPolicy} meets a request (ADR-001's enforcement path, as amended). This class holds
 * <b>no policy semantics</b>: every decision is asked of the compiled policy, which an ArchUnit rule and the
 * {@code core/policy} seam keep on the other side of the wall.
 *
 * <ul>
 *   <li>{@code PRESEARCH_REGISTERED} — narrow the query before it runs, so {@code _total} and paging are exact.
 *   <li>{@code PREACCESS_RESOURCES} — drop what still fails criteria: includes, GraphQL references, and — as a
 *       backstop — anything the narrowing missed, which is logged WARN (ADR-001's paging rule).
 *   <li>{@code PRESHOW_RESOURCES} — remove {@code hiddenFields}.
 * </ul>
 */
@Interceptor
public class PolicyEnforcementInterceptor {

    private final CriteriaEvaluator evaluator;
    private final PolicyDenialLog denialLog;

    public PolicyEnforcementInterceptor(CriteriaEvaluator evaluator, PolicyDenialLog denialLog) {
        this.evaluator = evaluator;
        this.denialLog = denialLog;
    }

    /**
     * Pre-query narrowing. Adding the criteria to the {@link SearchParameterMap} is what makes the database
     * return only what the caller may see, so {@code _count}, {@code _total} and {@code next} are exact
     * rather than filtered afterwards (V1 follow-up (a): this fires for GraphQL lists and nested searches too).
     */
    @Hook(Pointcut.STORAGE_PRESEARCH_REGISTERED)
    public void narrowSearch(RequestDetails request, SearchParameterMap map) {
        CompiledPolicy policy = PolicyContext.of(request);
        if (policy == null || policy.bypass() || request instanceof SystemRequestDetails) {
            return;
        }
        String resourceType = request.getResourceName();
        if (resourceType == null) {
            // HAPI runs internal searches with no resource name on the request — resolving a conditional
            // reference inside a transaction is one. There is nothing to narrow to, and the resources such a
            // search returns still pass through the PREACCESS drop, which knows each one's actual type.
            return;
        }
        Criteria criteria = policy.criteriaFor(resourceType, Interaction.SEARCH);
        if (criteria.allowsEverything()) {
            return;
        }
        if (criteria.deniesEverything()) {
            // nothing may match: a parameter no resource can satisfy is how a search returns empty rather
            // than 403, which is ADR-001's "collections filter silently"
            map.add("_id", new ca.uhn.fhir.rest.param.TokenParam(IMPOSSIBLE_ID));
            return;
        }
        if (criteria instanceof Criteria.Match match) {
            match.parameters().entrySet().forEach(entry -> entry.getValue()
                    .forEach(orList -> map.add(entry.getKey(), orList.stream().findFirst().orElse(null))));
        }
        // Criteria.AnyOf is left to PREACCESS: ORing two criteria into one SearchParameterMap would need
        // _filter, and ADR-001 says "ORed policies via _filter, else post-only". Post-only is correct and
        // safe; the paging consequence is documented in the ADR's paging rule.
    }

    /** No resource can have this id, so a denied search returns empty rather than failing the request. */
    private static final String IMPOSSIBLE_ID = "00000000-0000-0000-0000-000000000000";

    /**
     * The drop. Every resource on its way out, whatever brought it: a search entry, an {@code _include}, a
     * GraphQL reference, {@code $everything}, history. This is the one place that decides what a caller sees.
     */
    @Hook(Pointcut.STORAGE_PREACCESS_RESOURCES)
    public void dropWhatFailsCriteria(RequestDetails request, IPreResourceAccessDetails details) {
        CompiledPolicy policy = PolicyContext.of(request);
        if (policy == null || policy.bypass() || request instanceof SystemRequestDetails) {
            return;
        }
        boolean primaryQuery = isPrimaryQueryFor(request);
        for (int i = 0; i < details.size(); i++) {
            IBaseResource resource = details.getResource(i);
            if (resource == null) {
                continue;
            }
            String type = resource.fhirType();
            Criteria criteria = policy.criteriaFor(type, interactionFor(request));
            if (criteria.allowsEverything()) {
                continue;
            }
            if (!satisfies(criteria, resource)) {
                details.setDontReturnResourceAtIndex(i);
                String id = resource.getIdElement().getIdPart();
                if (primaryQuery && criteria instanceof Criteria.Match match) {
                    // the narrowing should have excluded this before the query ran
                    denialLog.narrowingMissed(type, id, match.source());
                } else {
                    denialLog.denied(callerOf(request), type, id, "read", policy, "criteria not satisfied");
                }
            }
        }
    }

    /** {@code hiddenFields}: removed after the drop, so a hidden field never reaches a caller. */
    @Hook(Pointcut.STORAGE_PRESHOW_RESOURCES)
    public void removeHiddenFields(RequestDetails request, IPreResourceShowDetails details) {
        CompiledPolicy policy = PolicyContext.of(request);
        if (policy == null || policy.bypass() || request instanceof SystemRequestDetails) {
            return;
        }
        for (int i = 0; i < details.size(); i++) {
            IBaseResource resource = details.getResource(i);
            if (resource == null) {
                continue;
            }
            List<String> hidden = policy.hiddenFieldsFor(resource.fhirType());
            if (hidden.isEmpty()) {
                continue;
            }
            FhirTerser terser = request.getFhirContext().newTerser();
            for (String path : hidden) {
                terser.getValues(resource, resource.fhirType() + "." + path, true, false)
                        .forEach(value -> terser.clear(value));
            }
        }
    }

    private boolean satisfies(Criteria criteria, IBaseResource resource) {
        return criteria.allowsEverything() || evaluator.satisfies(criteria, resource);
    }

    /** The tenant context the partition interceptor resolved, for the denial log; null on an internal call. */
    private static ProjectContext callerOf(RequestDetails request) {
        return request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext caller ? caller : null;
    }

    /** A search or read of the requested type is the primary query; an include or reference is not. */
    private static boolean isPrimaryQueryFor(RequestDetails request) {
        RestOperationTypeEnum operation = request.getRestOperationType();
        return operation == RestOperationTypeEnum.SEARCH_TYPE || operation == RestOperationTypeEnum.READ;
    }

    private static Interaction interactionFor(RequestDetails request) {
        RestOperationTypeEnum operation = request.getRestOperationType();
        if (operation == null) {
            return Interaction.READ;
        }
        return switch (operation) {
            case SEARCH_TYPE, SEARCH_SYSTEM -> Interaction.SEARCH;
            case HISTORY_INSTANCE, HISTORY_TYPE, HISTORY_SYSTEM -> Interaction.HISTORY;
            case VREAD -> Interaction.VREAD;
            default -> Interaction.READ;
        };
    }
}
