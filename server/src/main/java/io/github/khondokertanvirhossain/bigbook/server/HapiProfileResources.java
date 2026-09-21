package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import io.github.khondokertanvirhossain.bigbook.core.tenant.InviteRequest;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Project;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantProvisioner;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;

/**
 * The invitee's profile resource, created inside the project's HAPI partition (ADR-007 §2(f) step 4).
 *
 * <p>A <b>conditional</b> create on {@code identifier = email}, which is ADR-007's stable key for this step:
 * a retried invite finds the resource it made last time instead of making a second one. The write runs as a
 * {@link SystemRequestDetails} pinned to the partition, because provisioning happens before the invitee has
 * any token of their own — that is Big Book's internal system context, never token-reachable (T30).
 */
public class HapiProfileResources implements TenantProvisioner.ProfileResources {

    /** BB-R-005.4: the three types an invite may create. */
    private static final Set<String> PROFILE_TYPES = Set.of("Practitioner", "Patient", "RelatedPerson");

    /** v0.1 keeps an external id on the profile's identifier; it moves to the membership by v0.2 (T8). */
    static final String EXTERNAL_ID_SYSTEM = "https://bigbook.dev/external-id";

    private final DaoRegistry daoRegistry;

    public HapiProfileResources(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @Override
    public String ensureProfile(Project project, InviteRequest request, String email) {
        String type = request.resourceType();
        if (!PROFILE_TYPES.contains(type)) {
            throw new TenantException(400, "resourceType must be one of " + PROFILE_TYPES + ", not " + type + ".");
        }
        IBaseResource profile = switch (type) {
            case "Practitioner" -> practitioner(request, email);
            case "Patient" -> patient(request, email);
            default -> relatedPerson(request, email);
        };
        @SuppressWarnings("unchecked")
        IFhirResourceDao<IBaseResource> dao = (IFhirResourceDao<IBaseResource>) daoRegistry.getResourceDao(type);
        SystemRequestDetails asSystem = new SystemRequestDetails()
                .setRequestPartitionId(RequestPartitionId.fromPartitionId(project.partitionId()));
        return dao.create(profile, "identifier=" + email, asSystem)
                .getId()
                .toUnqualifiedVersionless()
                .getValue();
    }

    private IBaseResource practitioner(InviteRequest request, String email) {
        Practitioner practitioner = new Practitioner();
        practitioner.addIdentifier(identifier(email));
        externalId(request).ifPresent(practitioner::addIdentifier);
        practitioner.addName(name(request));
        return practitioner;
    }

    private IBaseResource patient(InviteRequest request, String email) {
        Patient patient = new Patient();
        patient.addIdentifier(identifier(email));
        externalId(request).ifPresent(patient::addIdentifier);
        patient.addName(name(request));
        return patient;
    }

    private IBaseResource relatedPerson(InviteRequest request, String email) {
        if (request.patient() == null || request.patient().isBlank()) {
            throw new TenantException(400, "A RelatedPerson invite needs the patient it relates to.");
        }
        RelatedPerson relatedPerson = new RelatedPerson();
        relatedPerson.addIdentifier(identifier(email));
        externalId(request).ifPresent(relatedPerson::addIdentifier);
        relatedPerson.addName(name(request));
        relatedPerson.setPatient(new Reference(request.patient()));
        return relatedPerson;
    }

    /** The email is the identifier the conditional create matches on, so it carries no system. */
    private static Identifier identifier(String email) {
        return new Identifier().setValue(email);
    }

    private static java.util.Optional<Identifier> externalId(InviteRequest request) {
        if (request.externalId() == null || request.externalId().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Identifier().setSystem(EXTERNAL_ID_SYSTEM).setValue(request.externalId()));
    }

    private static HumanName name(InviteRequest request) {
        HumanName name = new HumanName();
        if (request.lastName() != null) {
            name.setFamily(request.lastName());
        }
        if (request.firstName() != null) {
            name.addGiven(request.firstName());
        }
        return name;
    }
}
