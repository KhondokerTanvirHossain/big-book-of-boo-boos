package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOp;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.policy.Interaction;
import java.util.List;
import java.util.Set;

/**
 * The coarse half of ADR-001's split: <b>resource type × interaction</b>, and nothing else. Whether a caller
 * may search {@code Observation} at all is decided here; <i>which</i> Observations they see is decided by the
 * criteria hooks in {@link PolicyEnforcementInterceptor}.
 *
 * <p>Keeping the two apart is what makes the criteria enforcement possible. HAPI's own criteria-aware rule
 * testers fail closed by 403ing the whole request (ADR-001, V1), which would stop a criteria-scoped caller
 * from using {@code _include} or GraphQL at all. So the rules here are deliberately blunt: they never mention
 * criteria, and a caller permitted at this layer may still be shown nothing.
 *
 * <p>The default is deny. A type absent from the policy produces no {@code allow} rule and therefore falls
 * through to {@link #buildRuleList}'s closing {@code denyAll}, so an unlisted type is refused by the shape of
 * the rule list rather than by remembering to write a deny.
 */
public class PolicyAuthorizationInterceptor extends AuthorizationInterceptor {

    private final PolicyDenialLog denialLog;

    public PolicyAuthorizationInterceptor(PolicyDenialLog denialLog) {
        super(ca.uhn.fhir.rest.server.interceptor.auth.PolicyEnum.DENY);
        this.denialLog = denialLog;
    }

    @Override
    public List<IAuthRule> buildRuleList(RequestDetails request) {
        if (request instanceof SystemRequestDetails) {
            // HAPI's own reads — reindex jobs, subscription matching, the bootstrap. These carry no caller and
            // must not be filtered by one's policy; tenancy still confines them via the partition hooks.
            return new RuleBuilder().allowAll("system request").build();
        }
        CompiledPolicy policy = PolicyContext.of(request);
        if (policy == null) {
            // no policy resolved means the binder did not run or could not decide: refuse (D14)
            denialLog.denied(null, request.getResourceName(), null, "*", null, "no compiled policy on request");
            return new RuleBuilder().denyAll("no policy resolved").build();
        }
        if (policy.bypass()) {
            // only Project.superAdmin reaches here (T1); membership.admin gets injected admin-type entries
            return new RuleBuilder().allowAll("super-admin").build();
        }

        IAuthRuleBuilder rules = new RuleBuilder();
        for (CompiledPolicy.Entry entry : policy.entries()) {
            rules = appendRulesFor(rules, entry);
        }
        // metadata is public: /fhir/R4/metadata is how a client discovers the server, and refusing it would
        // break conformance for a caller who is otherwise entitled to read
        rules = rules.allow("capability statement").metadata().andThen();
        // $graphql is allowed *at this layer* and filtered at PREACCESS, which is the whole point of ADR-001's
        // split. Refusing it here would 403 the entire query because one referenced resource is out of reach —
        // the FhirQueryRuleTester behaviour V1 rejected, and it would fail ADR-001's exit test ("$graphql
        // ObservationList → only visible entries, no 403"). Every resource a GraphQL query reaches still passes
        // through the criteria drop, so allowing the operation does not widen what a caller can see.
        rules = rules.allow("graphql, filtered per resource at PREACCESS").graphQL().any().andThen();
        return rules.denyAll("outside policy").build();
    }

    /** One entry becomes one rule per interaction it permits, so an unlisted interaction has no rule. */
    private IAuthRuleBuilder appendRulesFor(IAuthRuleBuilder rules, CompiledPolicy.Entry entry) {
        if (entry.criteria().deniesEverything() || entry.interactions().isEmpty()) {
            // a refused criterion grants nothing at this layer either: no allow rule is emitted at all, so the
            // closing denyAll refuses the type. The criteria hooks would drop every resource anyway; this
            // makes a write fail with 403 rather than silently matching nothing.
            return rules;
        }
        String type = entry.resourceType();
        Set<Interaction> permitted = entry.interactions();
        String because = "policy " + type;
        if (permitted.stream().anyMatch(Interaction::read)) {
            rules = appliedTo(rules.allow(because + " read").read(), type);
        }
        if (permitted.contains(Interaction.CREATE)) {
            rules = appliedTo(rules.allow(because + " create").create(), type);
        }
        if (permitted.contains(Interaction.UPDATE)) {
            rules = appliedTo(rules.allow(because + " update").write(), type);
        }
        if (permitted.contains(Interaction.DELETE)) {
            rules = rules.allow(because + " delete").delete().resourcesOfType(type).withAnyId().andThen();
        }
        return rules;
    }

    /**
     * {@code "*"} is Medplum's wildcard and HAPI's {@code allResources()}; a named type is
     * {@code resourcesOfType}. {@code withAnyId} is deliberate — narrowing by id is the criteria layer's job.
     */
    private IAuthRuleBuilder appliedTo(IAuthRuleBuilderRuleOp op, String type) {
        var appliesTo = "*".equals(type) ? op.allResources() : op.resourcesOfType(type);
        return appliesTo.withAnyId().andThen();
    }
}
