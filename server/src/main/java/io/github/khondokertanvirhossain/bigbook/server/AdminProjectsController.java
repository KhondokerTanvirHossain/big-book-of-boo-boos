package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.khondokertanvirhossain.bigbook.core.tenant.InviteRequest;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Project;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantException;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantProvisioner;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
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

    /**
     * Invite (BB-R-005.4). **200 with the `ProjectMembership`, not 201** — Medplum's shape, and the one
     * admin route `@medplum/core` types (D2). Big Book's payload in v0.1; byte-compat is v0.2 (BB-R-014.4).
     *
     * <p>`sendEmail` is honoured but SMTP is **issue #11**: with none configured the invite succeeds and says
     * so in a warning `OperationOutcome`, rather than failing a seat that is otherwise complete (T5's shape).
     */
    @PostMapping("/{id}/invite")
    public JsonNode invite(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller,
            @PathVariable String id, @RequestBody ObjectNode body) {
        Project project = administered(caller, id);
        InviteRequest request = inviteRequest(body, caller);
        if (request.email() == null || request.email().isBlank()) {
            throw new TenantException(400, "An invite needs an email address.");
        }
        TenantProvisioner.Invited invited = provision(() -> provisioner.invite(project, request),
                "invite " + request.email() + " to project " + project.id());

        ObjectNode membership = (ObjectNode) render(invited.membership());
        if (invited.sendEmail()) {
            try {
                provisioner.sendInviteEmail(invited.user().id());
            } catch (RuntimeException noMail) {
                // the seat is active; only the email failed. Medplum returns 200 with a warning here (T5).
                log.warn("Invite for {} is active but no email was sent: {}", request.email(), noMail.toString());
                membership.set("issue", warning("The membership was created but no invite email could be sent: "
                        + noMail.getMessage() + " Configure SMTP (issue #11), or set sendEmail=false."));
            }
        }
        return membership;
    }

    /**
     * A machine identity (BB-R-005.5). 201 with the `ClientApplication`, secret included — and the secret
     * stays readable on every admin read of it (D5), unlike a "shown once" flow.
     */
    @PostMapping("/{id}/client")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createClient(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller,
            @PathVariable String id, @RequestBody ObjectNode body) {
        Project project = administered(caller, id);
        String name = body.path("name").asText("");
        if (name.isBlank()) {
            throw new TenantException(400, "A client needs a name.");
        }
        TenantProvisioner.ClientApplication client = provision(
                () -> provisioner.createClient(project, name,
                        body.hasNonNull("accessPolicy") ? body.get("accessPolicy").asText() : null),
                "create client '" + name + "' in project " + project.id());
        return renderClient(client.id().toString(), client.name(), client.secret(), client.membership());
    }

    /** The secret is readable here on every read, as in Medplum and Keycloak (D5). */
    @GetMapping("/{id}/clients/{clientId}")
    public JsonNode readClient(@RequestAttribute(ProjectContext.ATTRIBUTE) ProjectContext caller,
            @PathVariable String id, @PathVariable String clientId) {
        Project project = administered(caller, id);
        Membership seat = store.memberships(project.id(), "ClientApplication").stream()
                .filter(m -> ("ClientApplication/" + clientId).equals(m.profile()))
                .findFirst()
                .orElseThrow(() -> new TenantException(404, "ClientApplication " + clientId + " does not exist in this project."));
        return renderClient(clientId, seat.email(), provisioner.clientSecret(clientId), seat);
    }

    @ExceptionHandler(TenantException.class)
    public void refused(TenantException refusal, HttpServletResponse response) throws IOException {
        outcomes.write(response, refusal.status(), refusal.getMessage());
    }

    /** ADR-007: a step that fails leaves the row `provisioning` and the same request resumes it. */
    private <T> T provision(java.util.function.Supplier<T> step, String what) {
        try {
            return step.get();
        } catch (TenantException refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            log.warn("Could not finish: {}; its row stays provisioning", what, failure);
            throw new TenantException(503, "Could not finish: " + failure.getMessage()
                    + ". Nothing was rolled back; retry the same request to resume.", failure);
        }
    }

    private InviteRequest inviteRequest(ObjectNode body, ProjectContext caller) {
        // `membership` wins over the deprecated top-level admin/accessPolicy/access (T5)
        JsonNode membership = body.path("membership");
        return new InviteRequest(
                body.path("resourceType").asText("Practitioner"),
                body.path("firstName").asText(null),
                body.path("lastName").asText(null),
                body.path("email").asText(null),
                body.path("externalId").asText(null),
                membership.hasNonNull("patient") ? membership.get("patient").path("reference").asText() : body.path("patient").asText(null),
                body.path("scope").asText(null),
                body.path("password").asText(null),
                body.path("sendEmail").asBoolean(true),
                body.path("mfaRequired").asBoolean(false),
                admin(membership, body),
                reference(membership.has("accessPolicy") ? membership.get("accessPolicy") : body.get("accessPolicy")),
                (membership.has("access") ? membership.get("access") : body.get("access")) == null ? null
                        : (membership.has("access") ? membership.get("access") : body.get("access")).toString(),
                reference(membership.has("userConfiguration") ? membership.get("userConfiguration") : body.get("userConfiguration")),
                caller.membership().id());
    }

    /** `membership.admin` wins over the deprecated top-level `admin`; absent from both means "not set" (T5). */
    private static Boolean admin(JsonNode membership, ObjectNode body) {
        if (membership.has("admin")) {
            return membership.path("admin").asBoolean();
        }
        return body.has("admin") ? Boolean.valueOf(body.path("admin").asBoolean()) : null;
    }

    private static String reference(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.path("reference").asText(null);
    }

    private JsonNode renderClient(String clientId, String name, String secret, Membership seat) {
        ObjectNode node = json.createObjectNode();
        node.put("resourceType", "ClientApplication").put("id", clientId).put("name", name);
        if (secret != null) {
            node.put("secret", secret);
        }
        node.set("membership", render(seat));
        return node;
    }

    private JsonNode warning(String message) {
        ObjectNode outcome = json.createObjectNode();
        outcome.put("severity", "warning").put("code", "incomplete").put("diagnostics", message);
        return json.createArrayNode().add(outcome);
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
