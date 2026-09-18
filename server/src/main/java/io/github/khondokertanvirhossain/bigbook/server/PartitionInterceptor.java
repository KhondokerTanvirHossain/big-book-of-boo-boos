package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import io.github.khondokertanvirhossain.bigbook.core.ProjectContext;
import io.github.khondokertanvirhossain.bigbook.core.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.TenantStore;

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
        return RequestPartitionId.fromPartitionId(context(request).project().partitionId());
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
