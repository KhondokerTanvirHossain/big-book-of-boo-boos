package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition.IMutator;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.util.FhirTerser;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Criteria;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * The write side. Two phases, at the two pointcuts ADR-001's 2026-09-19 amendment names, for one reason:
 *
 * <ul>
 *   <li><b>Phase 1</b> at {@code PRESTORAGE_*} — the <i>existing</i> resource must be inside criteria, and
 *       {@code readonlyFields} are restored from it. This is HAPI's modify hook, so a restored field is what
 *       gets stored, and no reference resolution is needed to compare against the old state.
 *   <li><b>Phase 2</b> at {@code PRECOMMIT_*} — the <i>committed</i> state must be inside criteria. This is
 *       the only pointcut where a transaction entry's {@code urn:uuid} and conditional references are already
 *       rewritten, so one code path serves single writes and bundle entries alike. Throwing here rolls back
 *       the whole {@code transaction} and only the entry in a {@code batch}, which is the behaviour BB-R-006
 *       asks for and was measured on 8.12.1 (issue #7).
 * </ul>
 *
 * <p>Writes outside criteria are <b>403, never a silent filter</b> — the opposite of the read path. A caller
 * who is told "created" when nothing was stored has been lied to; a caller refused knows where they stand
 * (ADR-001, collection vs single-resource semantics).
 */
@Interceptor
public class PolicyWriteInterceptor {

    private final CriteriaEvaluator evaluator;
    private final PolicyDenialLog denialLog;

    public PolicyWriteInterceptor(CriteriaEvaluator evaluator, PolicyDenialLog denialLog) {
        this.evaluator = evaluator;
        this.denialLog = denialLog;
    }

    /** Phase 1 on create: there is no existing resource, so only the criteria on the new one are checked. */
    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
    public void beforeCreate(IBaseResource created, RequestDetails request) {
        requireInsideCriteria(request, created, Interaction.CREATE);
    }

    /**
     * Phase 1 on update: <b>both</b> states must be inside criteria, and {@code readonlyFields} are restored
     * from the old one. Checking the old state is what stops a caller editing a resource they cannot reach;
     * checking the new state here as well stops them moving one outside their own criteria.
     */
    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void beforeUpdate(IBaseResource existing, IBaseResource updated, RequestDetails request) {
        CompiledPolicy policy = policyFor(request);
        if (policy == null) {
            return;
        }
        requireInsideCriteria(request, existing, Interaction.UPDATE);
        requireInsideCriteria(request, updated, Interaction.UPDATE);
        restoreReadonlyFields(request, policy, existing, updated);
    }

    /** Phase 1 on delete: the resource as stored must be inside criteria. */
    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_DELETED)
    public void beforeDelete(IBaseResource deleted, RequestDetails request) {
        requireInsideCriteria(request, deleted, Interaction.DELETE);
    }

    /**
     * Phase 2 on create and update. References are resolved by now, so a criterion on a reference
     * ({@code Observation?subject=Patient/x}) is finally answerable for a transaction entry.
     */
    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void afterCreate(IBaseResource created, RequestDetails request) {
        requireInsideCriteria(request, created, Interaction.CREATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void afterUpdate(IBaseResource existing, IBaseResource updated, RequestDetails request) {
        requireInsideCriteria(request, updated, Interaction.UPDATE);
    }

    /**
     * {@code readonlyFields}: the stored value wins, silently. Medplum's semantics are "the field cannot be
     * changed", not "the write is refused", so a caller sending a new value gets a 200 and the old value —
     * which is why this restores rather than throws.
     *
     * <p>Done through the runtime child definition rather than {@link FhirTerser}: the terser can only
     * <i>set</i> string values, so restoring a composite field like {@code identifier} or {@code managingOrganization}
     * through it would flatten the element to a string and corrupt it. The mutator takes whole elements and
     * knows whether the field repeats, so one code path serves both.
     */
    private void restoreReadonlyFields(
            RequestDetails request, CompiledPolicy policy, IBaseResource existing, IBaseResource updated) {
        List<String> readonly = policy.readonlyFieldsFor(updated.fhirType());
        if (readonly.isEmpty()) {
            return;
        }
        BaseRuntimeElementCompositeDefinition<?> definition =
                (BaseRuntimeElementCompositeDefinition<?>) request.getFhirContext().getElementDefinition(updated.getClass());
        for (String field : readonly) {
            BaseRuntimeChildDefinition child = definition.getChildByName(field);
            if (child == null) {
                // a readonlyFields entry naming a field this type does not have: the validator refuses these
                // at write time, so there is nothing to restore and nothing to protect
                continue;
            }
            IMutator mutator = child.getMutator();
            mutator.setValue(updated, null);
            for (IBase stored : child.getAccessor().getValues(existing)) {
                mutator.addValue(updated, stored);
            }
        }
    }

    /** A write outside criteria is refused, and the refusal is logged with the policy that refused it. */
    private void requireInsideCriteria(RequestDetails request, IBaseResource resource, Interaction interaction) {
        CompiledPolicy policy = policyFor(request);
        if (policy == null || resource == null) {
            return;
        }
        String type = resource.fhirType();
        Criteria criteria = policy.criteriaFor(type, interaction);
        if (criteria.allowsEverything()) {
            return;
        }
        if (!evaluator.satisfies(criteria, resource)) {
            String id = resource.getIdElement() == null ? null : resource.getIdElement().getIdPart();
            denialLog.denied(callerOf(request), type, id, interaction.name(), policy, "write outside criteria");
            throw new ForbiddenOperationException(
                    "This " + type + " is outside the criteria of the caller's access policy");
        }
    }

    /** Null means no enforcement here: a system request, a super-admin, or no caller at all. */
    private CompiledPolicy policyFor(RequestDetails request) {
        if (request instanceof SystemRequestDetails) {
            return null;
        }
        CompiledPolicy policy = PolicyContext.of(request);
        return policy == null || policy.bypass() ? null : policy;
    }

    /** The tenant context the partition interceptor resolved, for the denial log; null on an internal call. */
    private static ProjectContext callerOf(RequestDetails request) {
        return request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext caller ? caller : null;
    }
}
