package io.github.khondokertanvirhossain.bigbook.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The negative half of ADR-003's allow-list. With Keycloak's hostname pinned to Big Book's URL, a client
 * may send any Keycloak path here; only {@code /realms/<realm>/**} and {@code /resources/**} are proxied
 * ({@link KeycloakProxyController}). The rest — {@code /admin/**}, {@code /realms/master/**},
 * {@code /metrics}, {@code /health} — are not Big Book's paths, so they answer <b>404</b>.
 *
 * <p>It runs before the security chain because a 401 would be the wrong answer: it invites a caller to
 * authenticate against an endpoint that does not exist here. Big Book's own {@code /actuator/health} is
 * untouched; only Keycloak's spelling is refused.
 */
public class KeycloakOriginNotHereFilter extends OncePerRequestFilter {

    private static final List<String> NOT_HERE = List.of("/admin", "/metrics", "/health");

    private final OperationOutcomes outcomes;

    public KeycloakOriginNotHereFilter(OperationOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // A path that still contains a traversal segment after the container's own normalisation is
        // refused outright: it cannot be routed safely, and it must never be *proxied*. Tomcat rejects
        // most of these before Spring sees them; this makes the answer the same 404 for all of them.
        if (path.contains("..") || path.contains("%2e") || path.contains("%2E")) {
            outcomes.write(response, 404, "Not found.");
            return;
        }
        // getRequestURI() is raw; getServletPath() is normalised. An excluded prefix in either is refused,
        // so a path that *becomes* /admin/... after normalisation cannot slip past this check.
        if (isNotHere(path) || isNotHere(request.getServletPath())) {
            outcomes.write(response, 404, "Not found.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** {@code /admin/projects} is Big Book's own; {@code /admin/**} on Keycloak's spelling is not. */
    private static boolean isNotHere(String path) {
        if (path.startsWith("/admin/projects") || path.equals("/admin/projects")) {
            return false;
        }
        if (path.startsWith("/realms/") && !path.startsWith("/realms/" + TenantConfig.REALM + "/")) {
            return true;
        }
        return NOT_HERE.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
