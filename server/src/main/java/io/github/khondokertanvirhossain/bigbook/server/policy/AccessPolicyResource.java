package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.model.api.annotation.Child;
import ca.uhn.fhir.model.api.annotation.ResourceDef;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;

/**
 * Medplum's {@code AccessPolicy} as a FHIR resource class, so HAPI's parser can round-trip it and the provider
 * can be registered by name. <b>Not a JPA resource</b> — the spike on #7 measured that HAPI JPA has no DAO for
 * a runtime-registered type ({@code HAPI-0572}), and the failure is structural: JPA generates DAOs, entities
 * and search indexes from the structures it ships. What {@code FhirContext} does give is parsing and name
 * resolution, which is all an {@link AccessPolicyProvider} needs.
 *
 * <p>The document itself is carried as a single opaque string rather than modelled field by field. That is
 * deliberate: modelling {@code resource[]} here would put the policy schema in two places — this class and
 * {@code PolicyCompiler.PolicyDocument} — and the compiler is the one that decides what a policy means. The
 * provider stores what it was sent and the compiler reads it; neither needs a FHIR child for every field.
 */
@ResourceDef(name = "AccessPolicy", profile = "https://medplum.com/fhir/StructureDefinition/AccessPolicy")
public class AccessPolicyResource extends DomainResource {

    private static final long serialVersionUID = 1L;

    @Child(name = "name", order = 0)
    private StringType name;

    public StringType getName() {
        return name;
    }

    public AccessPolicyResource setName(StringType name) {
        this.name = name;
        return this;
    }

    @Override
    public AccessPolicyResource copy() {
        AccessPolicyResource copy = new AccessPolicyResource();
        copyValues(copy);
        copy.name = name == null ? null : name.copy();
        return copy;
    }

    @Override
    public ResourceType getResourceType() {
        // there is no ResourceType constant for a Medplum admin type; null is what HAPI tolerates here, and
        // the provider identifies the type by its class, not by this enum
        return null;
    }

    @Override
    public String fhirType() {
        return "AccessPolicy";
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && (name == null || name.isEmpty());
    }
}
