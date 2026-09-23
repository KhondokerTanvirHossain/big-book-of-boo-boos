package io.github.khondokertanvirhossain.bigbook.server.policy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaRejectedException;
import io.github.khondokertanvirhossain.bigbook.core.policy.CriteriaValidator;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * {@code /fhir/R4/AccessPolicy}, served from Big Book's own table (ADR-001 storage consequence, confirmed by
 * the {@code @ResourceDef} spike on #7).
 *
 * <p>This is where <b>write-time criteria validation</b> happens, and it is the more important half of D15.
 * A criterion outside the evaluable subset is rejected here with a 400 naming the parameter, so a policy that
 * cannot be enforced never reaches storage. The compiler still turns anything that gets past this into
 * {@code Criteria.Never} — two independent guarantees, because a write-time check that is somehow bypassed
 * must not become an access grant (#7 acceptance criteria).
 *
 * <p>Every operation is scoped to the caller's project. A policy is written into the caller's project and read
 * only from it, which is the same boundary {@link AccessPolicyStore} enforces on the load path.
 */
public class AccessPolicyProvider implements IResourceProvider {

    private final AccessPolicyStore store;
    private final CriteriaValidator validator;
    private final ObjectMapper json;
    private final FhirContext fhirContext;

    public AccessPolicyProvider(
            AccessPolicyStore store, CriteriaValidator validator, ObjectMapper json, FhirContext fhirContext) {
        this.store = store;
        this.validator = validator;
        this.json = json;
        this.fhirContext = fhirContext;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return AccessPolicyResource.class;
    }

    @Create
    public MethodOutcome create(@ResourceParam String body, RequestDetails request) {
        UUID project = projectOf(request);
        JsonNode document = parse(body);
        validateCriteria(document);
        UUID id = UUID.randomUUID();
        store.save(project, id, nameOf(document), document.toString());
        IdType created = new IdType("AccessPolicy", id.toString(), "1");
        // the resource comes back in the body: Medplum's client reads the id off the response, and an empty
        // 201 with only a Location header would make it look like the create returned nothing
        return new MethodOutcome(created).setCreated(true).setResource(parsed(created, document.toString()));
    }

    @Update
    public MethodOutcome update(@IdParam IdType id, @ResourceParam String body, RequestDetails request) {
        UUID project = projectOf(request);
        UUID policyId = idOf(id);
        JsonNode document = parse(body);
        validateCriteria(document);
        if (!store.replace(project, policyId, nameOf(document), document.toString())) {
            // not in this project: 404 rather than 403, so the caller cannot learn that it exists elsewhere
            throw new ResourceNotFoundException("AccessPolicy/" + policyId + " is not known");
        }
        return new MethodOutcome(id).setResource(parsed(id, document.toString()));
    }

    @Read
    public AccessPolicyResource read(@IdParam IdType id, RequestDetails request) {
        return store.read(projectOf(request), idOf(id))
                .map(document -> parsed(id, document))
                .orElseThrow(() -> new ResourceNotFoundException("AccessPolicy/" + id.getIdPart() + " is not known"));
    }

    /**
     * Every {@code resource[].criteria} must be in the evaluable subset (V5). Rejection names the parameter,
     * because "your policy is invalid" does not tell an author which of five criteria to fix.
     */
    private void validateCriteria(JsonNode document) {
        for (JsonNode rule : document.path("resource")) {
            JsonNode criteria = rule.path("criteria");
            if (!criteria.isTextual() || criteria.asText().isBlank()) {
                continue;
            }
            try {
                validator.requireWritable(criteria.asText());
            } catch (CriteriaRejectedException rejected) {
                throw new InvalidRequestException(rejected.getMessage(), outcome(rejected));
            }
        }
    }

    /** The 400 body: an OperationOutcome whose expression points at the parameter that was refused. */
    private OperationOutcome outcome(CriteriaRejectedException rejected) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.INVALID);
        issue.setDiagnostics(rejected.getMessage());
        if (rejected.parameter() != null) {
            issue.addExpression("AccessPolicy.resource.criteria (" + rejected.parameter() + ")");
        }
        return outcome;
    }

    private AccessPolicyResource parsed(IdType id, String document) {
        AccessPolicyResource resource = new AccessPolicyResource();
        resource.setId(id.toUnqualifiedVersionless());
        JsonNode parsed = parse(document);
        if (parsed.path("name").isTextual()) {
            resource.setName(new org.hl7.fhir.r4.model.StringType(parsed.path("name").asText()));
        }
        return resource;
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (com.fasterxml.jackson.core.JacksonException notJson) {
            throw new InvalidRequestException("AccessPolicy body is not valid JSON");
        }
    }

    private static String nameOf(JsonNode document) {
        return document.path("name").isTextual() ? document.path("name").asText() : "";
    }

    private static UUID projectOf(RequestDetails request) {
        if (request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext caller) {
            return caller.project().id();
        }
        // no tenant context means no project to write into, and a policy with no project would be readable by
        // everyone; refuse rather than guess
        throw new ForbiddenOperationException("No project context for this request");
    }

    private static UUID idOf(IdType id) {
        try {
            return UUID.fromString(id.getIdPart());
        } catch (IllegalArgumentException notAnId) {
            throw new ResourceNotFoundException("AccessPolicy/" + id.getIdPart() + " is not known");
        }
    }
}
