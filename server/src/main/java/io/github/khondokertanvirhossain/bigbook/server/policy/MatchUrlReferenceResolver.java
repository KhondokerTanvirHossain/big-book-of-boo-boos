package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a conditional (match-URL) reference to its literal {@code Type/id}, for the phase-2 criteria check
 * only (ADR-001, ruled 2026-09-22 on #36).
 *
 * <p><b>Why this exists.</b> The copy handed to a {@code PRECOMMIT} interceptor has {@code urn:uuid}
 * placeholders substituted but conditional references left raw — two mechanisms, only one visible at the
 * pointcut (measured, 8.12.1, #36). Evaluating the raw copy is wrong in both directions: an unresolved
 * reference satisfies no criterion, so {@code Observation?subject=%patient} refuses a legitimate write; and it
 * equally fails the criterion that should have caught a cross-patient write, so the check passes over a string
 * rather than the resolved id.
 *
 * <p><b>Why a search and not HAPI's own map.</b> {@code getResolvedResourceId} is keyed by placeholder id and
 * returns nothing for a conditional reference; {@code getResolvedMatchUrls} has the entry but its value is the
 * internal JPA PID, not the FHIR id a criterion compares against (#36). Reading either in an authorization path
 * couples policy enforcement to HAPI internals. A search is the same operation the FHIR spec defines for a
 * conditional reference, through the public DAO. By the time {@code PRECOMMIT} fires HAPI has already resolved
 * the same URL and would have failed the bundle on zero or multiple matches, so this re-resolution is expected
 * to return exactly one and agree.
 *
 * <p><b>Fail closed, explicitly.</b> Zero matches, more than one match, or a throw all yield {@code null},
 * which the caller turns into a 403 and a rollback. "Not found" is never "carry on": that exact null-tolerance
 * committed a cross-patient Observation with 201 during #36's investigation.
 */
public class MatchUrlReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(MatchUrlReferenceResolver.class);

    /** Where the per-request resolution cache lives; one bundle can reference the same target repeatedly. */
    private static final String CACHE = MatchUrlReferenceResolver.class.getName() + ".cache";

    private final DaoRegistry daoRegistry;
    private final MatchUrlService matchUrlService;

    public MatchUrlReferenceResolver(DaoRegistry daoRegistry, MatchUrlService matchUrlService) {
        this.daoRegistry = daoRegistry;
        this.matchUrlService = matchUrlService;
    }

    /** Whether a reference value needs resolving before a criterion can be evaluated against it. */
    public static boolean isMatchUrl(String reference) {
        return reference != null && reference.contains("?");
    }

    /**
     * @return the literal {@code Type/id} the match URL names, or {@code null} when it cannot be established —
     *     which the caller must treat as a refusal, never as "leave the reference alone"
     */
    public String resolve(String matchUrl, RequestDetails request) {
        Map<String, String> cache = cacheFor(request);
        if (cache.containsKey(matchUrl)) {
            // a cached null is still a null: a URL that failed to resolve once must not resolve later in the
            // same request just because a second entry asked
            return cache.get(matchUrl);
        }
        String resolved = search(matchUrl, request);
        cache.put(matchUrl, resolved);
        return resolved;
    }

    private String search(String matchUrl, RequestDetails request) {
        String type = typeOf(matchUrl);
        if (type == null || !daoRegistry.isResourceTypeSupported(type)) {
            log.warn("phase 2: reference {} does not name a supported resource type; refusing the write", matchUrl);
            return null;
        }
        ProjectContext caller = callerOf(request);
        if (caller == null) {
            // no tenant context means no partition to search in, and searching the default partition would ask
            // about another tenant's data
            log.warn("phase 2: no project context to resolve {} in; refusing the write", matchUrl);
            return null;
        }
        try {
            SearchParameterMap parsed = matchUrlService.translateMatchUrl(matchUrl, definitionFor(type));
            parsed.setLoadSynchronousUpTo(2);
            List<IBaseResource> found = daoRegistry.getResourceDao(type)
                    .search(parsed, partitionedRequest(caller))
                    .getAllResources();
            if (found.size() != 1) {
                // zero or many: HAPI would have failed the bundle on either, so reaching here means the two
                // disagree. Refuse rather than guess which is right (ADR-001, #36)
                log.warn("phase 2: reference {} resolved to {} resources, not 1; refusing the write",
                        matchUrl, found.size());
                return null;
            }
            return found.get(0).getIdElement().toUnqualifiedVersionless().getValue();
        } catch (RuntimeException unresolvable) {
            // the only exit from this handler is null, which the caller turns into a 403 — see the fail-closed
            // standard pinned in PolicyArchitectureTest rule 4 before adding another handler like this
            log.warn("phase 2: reference {} could not be resolved; refusing the write", matchUrl, unresolvable);
            return null;
        }
    }

    private ca.uhn.fhir.context.RuntimeResourceDefinition definitionFor(String type) {
        return daoRegistry.getFhirContext().getResourceDefinition(type);
    }

    /** The caller's own partition, never the default: phase 2 must not resolve across tenants. */
    private static SystemRequestDetails partitionedRequest(ProjectContext caller) {
        SystemRequestDetails search = new SystemRequestDetails();
        search.setRequestPartitionId(RequestPartitionId.fromPartitionId(caller.project().partitionId()));
        return search;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cacheFor(RequestDetails request) {
        Object existing = request.getUserData().get(CACHE);
        if (existing instanceof Map<?, ?> cache) {
            return (Map<String, String>) cache;
        }
        Map<String, String> fresh = new HashMap<>();
        request.getUserData().put(CACHE, fresh);
        return fresh;
    }

    private static ProjectContext callerOf(RequestDetails request) {
        return request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext caller ? caller : null;
    }

    private static String typeOf(String matchUrl) {
        int cut = matchUrl.indexOf('?');
        return cut <= 0 ? null : matchUrl.substring(0, cut);
    }
}
