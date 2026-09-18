package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.khondokertanvirhossain.bigbook.core.Membership;
import io.github.khondokertanvirhossain.bigbook.core.Project;
import io.github.khondokertanvirhossain.bigbook.core.ProjectContext;
import io.github.khondokertanvirhossain.bigbook.core.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.TenantProvisioner;
import io.github.khondokertanvirhossain.bigbook.core.TenantStore;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project administration (BB-R-005.1, .12). Create is Medplum's {@code createProject}; the rest are
 * BB-only routes and are never documented as Medplum-compatible. Payloads are Big Book's in v0.1;
 * Medplum's byte shapes are v0.2 (BB-R-014.4).
 */
@RestController
@RequestMapping("/admin/projects")
public class AdminProjectsController {

    private static final Logger log = LoggerFactory.getLogger(AdminProjectsController.class);

    private final TenantStore store;
    private final TenantProvisioner provisioner;
    private final OperationOutcomes outcomes;
    private final ObjectMapper json;

    public AdminProjectsController(TenantStore store, TenantProvisioner provisioner, OperationOutcomes outcomes, ObjectMapper json) {
        this.store = store;
        this.provisioner = provisioner;
        this.outcomes = outcomes;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode create(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller, @RequestBody ObjectNode body) {
        requireSuperAdmin(caller);
        String name = body.path("name").asText("");
        if (name.isBlank()) {
            throw new TenantException(400, "A project needs a name.");
        }
        // only a super-admin may choose an id (BB-R-005.7); it is also what a retry keys on (ADR-007)
        UUID id = body.hasNonNull("id") ? id(body.get("id").asText()) : null;
        try {
            return render(provisioner.createProject(id, name, settings(body)));
        } catch (TenantException refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            log.warn("Create project '{}' did not finish; its row stays provisioning", name, failure);
            throw new TenantException(503, "The project could not be fully provisioned: " + failure.getMessage()
                    + ". Nothing was rolled back; retry the same request to resume.", failure);
        }
    }

    @GetMapping
    public List<JsonNode> list(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller) {
        requireSuperAdmin(caller);
        return store.projects().stream().map(this::render).toList();
    }

    @GetMapping("/{id}")
    public JsonNode read(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller, @PathVariable String id) {
        return render(administered(caller, id));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller, @PathVariable String id, @RequestBody ObjectNode body) {
        Project project = administered(caller, id);
        store.updateProject(project.id(), body.path("name").asText(project.name()), settings(body));
        return render(store.project(project.id()).orElseThrow());
    }

    @GetMapping("/{id}/members")
    public List<JsonNode> members(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller, @PathVariable String id,
            @RequestParam(required = false) String profileType) {
        return store.memberships(administered(caller, id).id(), profileType).stream().map(this::render).toList();
    }

    @PutMapping("/{id}/members/{membershipId}")
    public JsonNode updateMember(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller, @PathVariable String id,
            @PathVariable String membershipId, @RequestBody ObjectNode body) {
        Project project = administered(caller, id);
        Membership seat = store.membership(id(membershipId))
                .filter(found -> found.projectId().equals(project.id()))
                .orElseThrow(() -> new TenantException(404, "Membership " + membershipId + " does not exist in this project."));
        store.updateMembership(seat.id(),
                body.path("admin").asBoolean(seat.admin()),
                body.hasNonNull("accessPolicy") ? body.get("accessPolicy").asText() : seat.accessPolicy(),
                body.has("access") ? body.get("access").toString() : seat.access(),
                body.hasNonNull("userConfiguration") ? body.get("userConfiguration").asText() : seat.userConfiguration());
        return render(store.membership(seat.id()).orElseThrow());
    }

    @ExceptionHandler(TenantException.class)
    public void refused(TenantException refusal, HttpServletResponse response) throws IOException {
        outcomes.write(response, refusal.status(), refusal.getMessage());
    }

    private static void requireSuperAdmin(ProjectContext caller) {
        if (!caller.superAdmin()) {
            throw new TenantException(403, "Only a super-admin may do this.");
        }
    }

    /** The path id wins over any {@code X-Project} (BB-R-005.11). A project still provisioning is 404 to everyone else. */
    private Project administered(ProjectContext caller, String projectId) {
        Project project = store.project(id(projectId)).filter(found -> found.active() || caller.superAdmin())
                .orElseThrow(() -> new TenantException(404, "Project " + projectId + " does not exist."));
        if (!caller.mayAdminister(project.id())) {
            throw new TenantException(403, "Not an admin of project " + projectId + ".");
        }
        return project;
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            throw new TenantException(400, "'" + value + "' is not an id.");
        }
    }

    /** Everything in the body except what Big Book owns is the project's settings (BB-R-005.6). */
    private static String settings(ObjectNode body) {
        ObjectNode settings = body.deepCopy();
        settings.remove(List.of("resourceType", "id", "name", "status", "superAdmin"));
        return settings.toString();
    }

    private JsonNode render(Project project) {
        try {
            ObjectNode node = (ObjectNode) json.readTree(project.settings());
            node.put("resourceType", "Project").put("id", project.id().toString()).put("name", project.name()).put("status", project.status());
            if (project.superAdmin()) {
                node.put("superAdmin", true);
            }
            return node;
        } catch (IOException unreadable) {
            throw new IllegalStateException("Settings of project " + project.id() + " are not JSON", unreadable);
        }
    }

    private JsonNode render(Membership seat) {
        try {
            ObjectNode node = json.createObjectNode();
            node.put("resourceType", "ProjectMembership").put("id", seat.id().toString());
            node.putObject("project").put("reference", "Project/" + seat.projectId());
            node.put("email", seat.email()).put("admin", seat.admin()).put("status", seat.status());
            if (seat.userId() != null) {
                node.putObject("user").put("reference", "User/" + seat.userId());
            }
            if (seat.profile() != null) {
                node.putObject("profile").put("reference", seat.profile());
            }
            if (seat.accessPolicy() != null) {
                node.putObject("accessPolicy").put("reference", seat.accessPolicy());
            }
            node.set("access", json.readTree(seat.access()));
            if (seat.userConfiguration() != null) {
                node.putObject("userConfiguration").put("reference", seat.userConfiguration());
            }
            return node;
        } catch (IOException unreadable) {
            throw new IllegalStateException("Access list of membership " + seat.id() + " is not JSON", unreadable);
        }
    }
}
