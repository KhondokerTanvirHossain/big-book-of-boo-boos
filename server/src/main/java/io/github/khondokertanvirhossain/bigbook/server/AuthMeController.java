package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Project;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /auth/me} (BB-R-004.8, ADR-003 option B) — the one call that tells a caller who it is, and
 * the only endpoint Big Book writes itself in the auth area.
 *
 * <p>For a user token this is where {@code profile} and {@code membership} come from at all: they vary per
 * project for one Keycloak user, so they are not token claims (ADR-003, amended 2026-09-19). The project
 * and seat were already resolved once for this request by {@link ProjectContextFilter}, so this is a
 * projection, not a lookup.
 *
 * <p>v0.1 returns what exists today. {@code config} with a non-empty {@code menu} and {@code accessPolicy}
 * as the compiled effective policy are what the SDK needs at SDK-grade, which is v0.2 (BB-R-014.3, D4);
 * {@code profile} is null until the invite flow creates profile resources (issue #6).
 */
@RestController
public class AuthMeController {

    private final ObjectMapper json;

    public AuthMeController(ObjectMapper json) {
        this.json = json;
    }

    @GetMapping("/auth/me")
    public JsonNode me(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller) {
        Project project = caller.project();
        Membership seat = caller.membership();
        ObjectNode me = json.createObjectNode();

        ObjectNode projectNode = me.putObject("project");
        projectNode.put("resourceType", "Project").put("id", project.id().toString()).put("name", project.name());
        if (project.superAdmin()) {
            projectNode.put("superAdmin", true);
        }

        ObjectNode membershipNode = me.putObject("membership");
        membershipNode.put("resourceType", "ProjectMembership").put("id", seat.id().toString());
        membershipNode.put("admin", seat.admin());
        membershipNode.putObject("project").put("reference", "Project/" + seat.projectId());
        if (seat.userId() != null) {
            membershipNode.putObject("user").put("reference", "User/" + seat.userId());
        }
        if (seat.profile() != null) {
            membershipNode.putObject("profile").put("reference", seat.profile());
        }

        // the profile resource itself arrives with the invite flow (issue #6); null is the honest answer
        me.set("profile", seat.profile() == null ? null : membershipNode.get("profile"));
        return me;
    }
}
