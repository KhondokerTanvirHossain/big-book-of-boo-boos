package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.partition.BaseRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;

/** One project is one HAPI partition: every read and write goes to the partition of the request's project. */
@Interceptor
public class PartitionInterceptor {

    private final TenantStore store;
    private final PartitionSettings settings;

    public PartitionInterceptor(TenantStore store, PartitionSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /**
     * Medplum's {@code _project=<id>} and {@code _compartment=Project/<id>} (BB-R-002.6, T26). They mean
     * what {@code X-Project} means, so they go through the same rule, and HAPI never sees them.
     */
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    public void projectParameters(RequestDetails request) {
        for (String name : new String[] {"_project", "_compartment"}) {
            String[] values = request.getParameters().get(name);
            if (values == null || values.length == 0) {
                continue;
            }
            if (name.equals("_compartment") && !values[0].startsWith("Project/")) {
                continue;
            }
            String selected = values[0].substring(values[0].indexOf('/') + 1);
            ProjectContext context = context(request);
            try {
                boolean own = selected.equals(context.project().id().toString());
                request.setAttribute(ProjectContext.ATTRIBUTE, own ? context : ProjectContextFilter.retarget(store, context, selected));
            } catch (TenantException refusal) {
                throw asHapi(refusal);
            }
            request.removeParameter(name);
        }
    }

    @Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)
    public RequestPartitionId identify(RequestDetails request) {
        if (request instanceof SystemRequestDetails) {
            // HAPI's own housekeeping, and Big Book's internal context: never reachable with a token (T30)
            return RequestPartitionId.defaultPartition(settings);
        }
        if (serverWide(request.getResourceName())) {
            // Types HAPI declares non-partitionable are server-wide infrastructure — SearchParameter,
            // CodeSystem, StructureDefinition and the rest of its list. Returning a tenant partition for one
            // is refused with HAPI-1318, which is what blocked runtime SearchParameter registration (#9, V4).
            // Asking HAPI for the set rather than keeping a copy: a hard-coded list would drift on upgrade.
            return RequestPartitionId.defaultPartition(settings);
        }
        return RequestPartitionId.fromPartitionId(context(request).project().partitionId());
    }

    /**
     * Whether a type is one HAPI refuses to partition.
     *
     * <p><b>These are shared across tenants</b>, which is a real consequence and not a loophole: a
     * {@code SearchParameter} one project registers is visible to every project, because HAPI's index is
     * server-wide. BB-R-002 wants runtime search parameters (V4) and HAPI cannot scope them per tenant, so the
     * sharing is recorded as a divergence in {@code medplum-parity.md} rather than hidden here. Writing them is
     * still governed by the policy layer, so an ordinary member cannot register one.
     */
    private static boolean serverWide(String resourceType) {
        return resourceType != null
                && BaseRequestPartitionHelperSvc.NON_PARTITIONABLE_RESOURCE_NAMES.contains(resourceType);
    }

    private static ProjectContext context(RequestDetails request) {
        if (request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext context) {
            return context;
        }
        throw new AuthenticationException("No project for this request.");
    }

    private static BaseServerResponseException asHapi(TenantException refusal) {
        return switch (refusal.status()) {
            case 403 -> new ForbiddenOperationException(refusal.getMessage());
            case 404 -> new ResourceNotFoundException(refusal.getMessage());
            default -> new InvalidRequestException(refusal.getMessage());
        };
    }
}
