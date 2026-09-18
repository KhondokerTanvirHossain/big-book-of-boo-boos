package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.graphql.GraphQLProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #4's acceptance criteria. Two projects and one user with a seat in both, admin in A and member
 * in B; memberships seeded directly, tokens obtained from Keycloak with organization:&lt;alias&gt;.
 */
class TenantIsolationTest extends LiteStackTest {

    static UUID projectA;
    static UUID projectB;
    static String user;

    @Autowired
    JpaStorageSettings storageSettings;

    @Autowired
    RestfulServer fhirServer;

    @Autowired
    GraphQLProvider graphQLProvider;

    @BeforeEach
    void twoProjectsOneUser() {
        if (projectA != null) {
            return;
        }
        String superAdmin = superAdminToken();
        projectA = createProject(superAdmin, "Clinic");
        // the same display name on purpose: names are not keys (BB-R-005.14)
        projectB = createProject(superAdmin, "Clinic");
        user = "Shared.User-" + UUID.randomUUID() + "@bigbook.test";
        String userId = seedUser(user);
        seedSeat(projectA, user, userId, true);
        seedSeat(projectB, user, userId, false);
    }

    @Test
    void aPatientCreatedInAIsNotFoundUnderBsToken() {
        String inA = create(tokenFor(user, projectA), "Patient", patient("Isolated"));

        assertThat(call(HttpMethod.GET, "/fhir/R4/" + inA, tokenFor(user, projectA), null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/fhir/R4/" + inA, tokenFor(user, projectB), null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient?family=Isolated", tokenFor(user, projectB), null, JsonNode.class, "Cache-Control", "no-cache")
                .getBody().path("total").asInt()).isZero();
    }

    @Test
    void aReferenceIntoAnotherProjectIsRejectedOnWrite() {
        String inA = create(tokenFor(user, projectA), "Patient", patient("Referenced"));

        ResponseEntity<JsonNode> rejected = call(HttpMethod.POST, "/fhir/R4/Observation", tokenFor(user, projectB), observation(inA), JsonNode.class);

        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        assertThat(rejected.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
    }

    @Test
    void xProjectSelectsThePartitionForASuperAdminOnly() {
        String superAdmin = superAdminToken();
        String inB = call(HttpMethod.POST, "/fhir/R4/Patient", superAdmin, patient("ViaHeader"), JsonNode.class, "X-Project", projectB.toString())
                .getBody().path("id").asText();
        String inOwn = call(HttpMethod.POST, "/fhir/R4/Patient", superAdmin, patient("NoHeader"), JsonNode.class).getBody().path("id").asText();

        assertThat(status("/fhir/R4/Patient/" + inB, tokenFor(user, projectB))).isEqualTo(HttpStatus.OK);
        assertThat(status("/fhir/R4/Patient/" + inB, tokenFor(user, projectA))).isEqualTo(HttpStatus.NOT_FOUND);
        // without the header it is no error (D1): the write lands in the super-admin's own project
        assertThat(status("/fhir/R4/Patient/" + inOwn, superAdmin)).isEqualTo(HttpStatus.OK);
        assertThat(status("/fhir/R4/Patient/" + inOwn, tokenFor(user, projectB))).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> refused = call(HttpMethod.GET, "/fhir/R4/Patient", tokenFor(user, projectA), null, JsonNode.class, "X-Project", projectA.toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
    }

    @Test
    void compartmentProjectFiltersToThatPartitionOnly() {
        create(tokenFor(user, projectA), "Patient", patient("Compartment"));
        String superAdmin = superAdminToken();

        assertThat(total("/fhir/R4/Patient?family=Compartment&_compartment=Project/" + projectA, superAdmin)).isEqualTo(1);
        assertThat(total("/fhir/R4/Patient?family=Compartment&_project=" + projectB, superAdmin)).isZero();
        assertThat(total("/fhir/R4/Patient?family=Compartment&_compartment=Project/" + projectA, tokenFor(user, projectA))).isEqualTo(1);
        assertThat(status("/fhir/R4/Patient?_compartment=Project/" + projectB, tokenFor(user, projectA))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aTokenCarryingMoreThanOneOrganisationIsRefused() {
        ResponseEntity<JsonNode> refused = call(HttpMethod.GET, "/fhir/R4/Patient", token(user, USER_PASSWORD, "openid organization:*"), null, JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(refused.getBody().path("issue").path(0).path("diagnostics").asText()).contains("2 organisations");
    }

    @Test
    void aTokenBoundToNoProjectOrToAProjectWithoutASeatIsRefused() {
        assertThat(status("/fhir/R4/Patient", token(user, USER_PASSWORD, "openid"))).isEqualTo(HttpStatus.BAD_REQUEST);

        // in the Keycloak organisation, so Keycloak issues the token, but with no Big Book seat
        UUID projectC = createProject(superAdminToken(), "No seat");
        String outsider = "outsider-" + UUID.randomUUID() + "@bigbook.test";
        String outsiderId = seedUser(outsider);
        new io.github.khondokertanvirhossain.bigbook.core.KeycloakDirectory(keycloak.realm(TenantConfig.REALM))
                .ensureOrganizationMember(keycloak.realm(TenantConfig.REALM).organizations().search(projectC.toString(), true, 0, 1).get(0).getId(), outsiderId);

        assertThat(status("/fhir/R4/Patient", tokenFor(outsider, projectC))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void emailsAreOneSeatWhateverTheirCase() {
        Map<String, Object> seat = jdbc.sql("SELECT email FROM bigbook.project_membership WHERE project_id = :project")
                .param("project", projectA).query().singleRow();

        assertThat(seat.get("email")).isEqualTo(user.toLowerCase());
        store.insertProvisioningMembership(projectA, user.toUpperCase(), false);
        assertThat(jdbc.sql("SELECT count(*) FROM bigbook.project_membership WHERE project_id = :project")
                .param("project", projectA).query(Long.class).single()).isEqualTo(1);
    }

    /**
     * The check V1 could not run (issue #9): a resource in A that points at a resource in B. HAPI refuses
     * to store such a reference, so it is planted with referential integrity switched off for one write.
     */
    @Test
    void anIncludeOrAGraphQlReferenceNeverCrossesAProject() {
        String tokenA = tokenFor(user, projectA);
        String inB = create(tokenFor(user, projectB), "Patient", patient("OtherTenant"));
        storageSettings.setEnforceReferentialIntegrityOnWrite(false);
        String observation;
        try {
            observation = create(tokenA, "Observation", observation(inB));
        } finally {
            storageSettings.setEnforceReferentialIntegrityOnWrite(true);
        }
        fhirServer.registerProvider(graphQLProvider);

        JsonNode included = call(HttpMethod.GET, "/fhir/R4/Observation?_id=" + observation.substring(12) + "&_include=Observation:subject",
                tokenA, null, JsonNode.class, "Cache-Control", "no-cache").getBody();
        ResponseEntity<String> graphql = call(HttpMethod.POST, "/fhir/R4/" + observation + "/$graphql", tokenA,
                Map.of("query", "{id subject{reference resource{...on Patient{id name{family}}}}}"), String.class);

        assertThat(included.path("entry")).hasSize(1);
        assertThat(included.path("entry").path(0).path("resource").path("resourceType").asText()).isEqualTo("Observation");
        assertThat(included.toString()).doesNotContain("OtherTenant");
        // HAPI does not blank the field: it fails the query exactly as for an id that exists nowhere
        // (HAPI-2001 "is not known"), so the caller cannot even tell that B has such a resource
        assertThat(graphql.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(graphql.getBody()).contains("OperationOutcome").contains("is not known").doesNotContain("OtherTenant");

        // control: the same two requests inside one project do return the Patient, so the above can fail
        String sameProject = create(tokenA, "Observation", observation(create(tokenA, "Patient", patient("SameTenant"))));
        JsonNode includedInA = call(HttpMethod.GET, "/fhir/R4/Observation?_id=" + sameProject.substring(12) + "&_include=Observation:subject",
                tokenA, null, JsonNode.class, "Cache-Control", "no-cache").getBody();
        ResponseEntity<String> graphqlInA = call(HttpMethod.POST, "/fhir/R4/" + sameProject + "/$graphql", tokenA,
                Map.of("query", "{id subject{reference resource{...on Patient{id name{family}}}}}"), String.class);
        assertThat(includedInA.path("entry")).hasSize(2);
        assertThat(graphqlInA.getBody()).contains("SameTenant");
    }

    private UUID createProject(String superAdmin, String name) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdmin, Map.of("name", name), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("status").asText()).isEqualTo("active");
        return UUID.fromString(created.getBody().path("id").asText());
    }

    private String create(String bearer, String type, String body) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/" + type, bearer, body, JsonNode.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return type + "/" + created.getBody().path("id").asText();
    }

    private HttpStatus status(String url, String bearer) {
        return HttpStatus.valueOf(call(HttpMethod.GET, url, bearer, null, String.class).getStatusCode().value());
    }

    private int total(String url, String bearer) {
        return call(HttpMethod.GET, url, bearer, null, JsonNode.class, "Cache-Control", "no-cache").getBody().path("total").asInt();
    }

    private static String patient(String family) {
        return "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"" + family + "\"}]}";
    }

    private static String observation(String subject) {
        return "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"x\"},\"subject\":{\"reference\":\"" + subject + "\"}}";
    }
}
