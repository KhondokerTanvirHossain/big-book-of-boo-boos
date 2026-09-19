package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import io.github.khondokertanvirhossain.bigbook.core.ProjectContext;
import java.util.Set;

/**
 * INTERIM (issue #8, to be removed by #7). HAPI advertises operations that mutate data or read across
 * projects on every resource type; the default access policy that should deny them is BB-R-006, issue #7.
 * Until then these are super-admin only, so a project user cannot hard-delete history or count another
 * project's resources.
 *
 * <p>Issue #7 replaces this with the compiled policy and deletes this class in the same PR.
 */
@Interceptor
public class InterimOperationDenyInterceptor {

    /** Lower-case, as HAPI reports {@code getOperation()}; the leading {@code $} is part of the name. */
    static final Set<String> SUPER_ADMIN_ONLY = Set.of(
            "$expunge",
            "$mark-all-resources-for-reindexing",
            "$perform-reindexing-pass",
            "$reindex",
            "$reindex-terminology",
            "$get-resource-counts",
            "$meta-add",
            "$meta-delete",
            "$hapi.fhir.merge",
            "$hapi.fhir.undo-merge",
            "$hapi.fhir.replace-references",
            "$hapi.fhir.undo-replace-references");

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    public void denyUnlessSuperAdmin(RequestDetails request) {
        String operation = request.getOperation();
        if (operation == null || !SUPER_ADMIN_ONLY.contains(operation.toLowerCase())) {
            return;
        }
        if (!(request.getAttribute(ProjectContext.ATTRIBUTE) instanceof ProjectContext context) || !context.superAdmin()) {
            throw new ForbiddenOperationException(
                    operation + " is restricted to super-admins until the default access policy lands (issue #7).");
        }
    }
}
