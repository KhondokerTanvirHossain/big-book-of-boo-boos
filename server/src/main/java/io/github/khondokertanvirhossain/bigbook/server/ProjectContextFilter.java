package io.github.khondokertanvirhossain.bigbook.server;

import io.github.khondokertanvirhossain.bigbook.core.Membership;
import io.github.khondokertanvirhossain.bigbook.core.Project;
import io.github.khondokertanvirhossain.bigbook.core.ProjectContext;
import io.github.khondokertanvirhossain.bigbook.core.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.TenantStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller's project from a validated bearer token, once per request (issue #4). The project
 * is Keycloak's own {@code organization} claim, whose alias is the project id (ADR-007); the seat is
 * looked up by (project, {@code sub}). One query for an ordinary call.
 */
public class ProjectContextFilter extends OncePerRequestFilter {

    /** Big Book extension, optional, honoured for super-admins only (BB-R-005.11). */
    static final String X_PROJECT = "X-Project";

    private final TenantStore store;
    private final OperationOutcomes outcomes;

    public ProjectContextFilter(TenantStore store, OperationOutcomes outcomes) {
        this.store = store;
        this.outcomes = outcomes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            try {
                request.setAttribute(ProjectContext.ATTRIBUTE, resolve(store, token.getToken(), request.getHeader(X_PROJECT)));
            } catch (TenantException refusal) {
                outcomes.write(response, refusal.status(), refusal.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static ProjectContext resolve(TenantStore store, Jwt token, String selectedProject) {
        List<String> organizations = organizations(token.getClaim("organization"));
        if (organizations.isEmpty()) {
            throw new TenantException(400, "The token is not bound to a project. Request scope organization:<project id>.");
        }
        if (organizations.size() > 1) {
            throw new TenantException(400, "The token carries " + organizations.size()
                    + " organisations. Request scope organization:<project id> for exactly one project.");
        }
        Project own = store.project(projectId(organizations.get(0)))
                .orElseThrow(() -> new TenantException(404, "Project " + organizations.get(0) + " does not exist."));
        Membership seat = store.membership(own.id(), token.getSubject())
                .filter(Membership::active)
                .orElseThrow(() -> new TenantException(403, "No active membership in project " + own.id() + "."));
        if (!own.active()) {
            // BB-R-005.13: a project still provisioning does not exist for its members
            throw new TenantException(404, "Project " + own.id() + " does not exist.");
        }
        return retarget(store, new ProjectContext(own, seat, own.superAdmin()), selectedProject);
    }

    /** A super-admin may act on another project; anyone else naming one, even their own, is refused. */
    static ProjectContext retarget(TenantStore store, ProjectContext context, String selectedProject) {
        if (selectedProject == null) {
            return context;
        }
        if (!context.superAdmin()) {
            throw new TenantException(403, "Only a super-admin may select a project.");
        }
        Project target = store.project(projectId(selectedProject))
                .orElseThrow(() -> new TenantException(404, "Project " + selectedProject + " does not exist."));
        return new ProjectContext(target, context.membership(), true);
    }

    private static UUID projectId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            throw new TenantException(400, "'" + value + "' is not a project id.");
        }
    }

    /** Keycloak emits a list of aliases; with organisation attributes mapped it emits a map keyed by alias. */
    private static List<String> organizations(Object claim) {
        if (claim instanceof Collection<?> aliases) {
            return aliases.stream().map(String::valueOf).toList();
        }
        if (claim instanceof Map<?, ?> byAlias) {
            return byAlias.keySet().stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
