package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.khondokertanvirhossain.bigbook.core.Project;
import io.github.khondokertanvirhossain.bigbook.core.TenantProvisioner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ADR-007's exit test for create-project, and the routes of BB-R-005.12. */
class ProjectProvisioningTest extends LiteStackTest {

    @Autowired
    IPartitionLookupSvc partitions;

    @Autowired
    TenantProvisioner provisioner;

    /** Keycloak stops answering between the anchor row and the organisation. */
    @Test
    void keycloakDownAfterTheAnchorRow() {
        String superAdmin = superAdminToken();
        String name = "Interrupted-" + UUID.randomUUID();
        int organisationsBefore = organisations();
        // the realm's keys are cached from here on, so the token still validates while Keycloak is silent
        // and the failure lands where this test means it to: after the anchor row, before the organisation
        assertThat(call(HttpMethod.GET, "/admin/projects", superAdmin, null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        KEYCLOAK.getDockerClient().pauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        ResponseEntity<JsonNode> failed;
        try {
            failed = call(HttpMethod.POST, "/admin/projects", superAdmin, Map.of("name", name), JsonNode.class);
        } finally {
            KEYCLOAK.getDockerClient().unpauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        }

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        Project anchor = store.provisioningProjectNamed(name).orElseThrow();
        assertThat(anchor.status()).isEqualTo("provisioning");
        assertThat(organisations()).isEqualTo(organisationsBefore);

        assertResumes(superAdmin, name, anchor, organisationsBefore + 1);
    }

    /** The organisation exists, then the HAPI partition cannot be created. */
    @Test
    void hapiFailsAfterTheOrganisationExists() {
        String superAdmin = superAdminToken();
        String name = "Interrupted-" + UUID.randomUUID();
        int organisationsBefore = organisations();
        // the next project will be given this partition id; occupy it so that HAPI refuses (HAPI-2366)
        int nextPartitionId = jdbc.sql("SELECT last_value + 1 FROM bigbook.project_partition_id_seq").query(Integer.class).single();
        PartitionEntity squatter = new PartitionEntity();
        squatter.setId(nextPartitionId);
        squatter.setName("squatter-" + nextPartitionId);
        partitions.createPartition(squatter, new SystemRequestDetails());

        ResponseEntity<JsonNode> failed = call(HttpMethod.POST, "/admin/projects", superAdmin, Map.of("name", name), JsonNode.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Project anchor = store.provisioningProjectNamed(name).orElseThrow();
        assertThat(anchor.partitionId()).isEqualTo(nextPartitionId);
        assertThat(organisations()).as("the organisation was created before the failure and is kept").isEqualTo(organisationsBefore + 1);

        // a member's token against a project that is still provisioning: it does not exist for them (BB-R-005.13)
        String member = "early-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(anchor.id(), member, seedUser(member), true);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient", tokenFor(member, anchor.id()), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/admin/projects/" + anchor.id(), tokenFor(member, anchor.id()), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // the super-admin sees it, with its status
        assertThat(call(HttpMethod.GET, "/admin/projects", superAdmin, null, JsonNode.class).getBody().toString())
                .contains("\"" + anchor.id() + "\"").contains("provisioning");

        partitions.deletePartition(nextPartitionId); // the test's own obstacle, not part of any project
        assertResumes(superAdmin, name, anchor, organisationsBefore + 1);
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient", tokenFor(member, anchor.id()), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theStartupReconcilerFinishesAnInterruptedCreate() {
        UUID id = UUID.randomUUID();
        store.insertProvisioning(id, "Reconciled", false, "{}");

        assertThat(provisioner.reconcile(5)).as("younger than five minutes: may be in flight, left alone").isZero();
        assertThat(store.project(id).orElseThrow().status()).isEqualTo("provisioning");

        jdbc.sql("UPDATE bigbook.project SET created_at = now() - interval '10 minutes' WHERE id = :id").param("id", id).update();
        assertThat(provisioner.reconcile(5)).isZero();
        assertThat(store.project(id).orElseThrow().status()).isEqualTo("active");
        assertThat(partitions.getPartitionByName(id.toString()).getId()).isEqualTo(store.project(id).orElseThrow().partitionId());
    }

    @Test
    void projectRoutesAreForSuperAdminsAndProjectAdmins() {
        String superAdmin = superAdminToken();
        UUID id = UUID.fromString(call(HttpMethod.POST, "/admin/projects", superAdmin,
                Map.of("name", "Routes", "checkReferencesOnWrite", true, "features", new String[] {"bots"}), JsonNode.class).getBody().path("id").asText());
        String admin = "admin-" + UUID.randomUUID() + "@bigbook.test";
        String member = "member-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(id, admin, seedUser(admin), true);
        seedSeat(id, member, seedUser(member), false);
        String adminToken = tokenFor(admin, id);
        String memberToken = tokenFor(member, id);

        JsonNode read = call(HttpMethod.GET, "/admin/projects/" + id, adminToken, null, JsonNode.class).getBody();
        assertThat(read.path("name").asText()).isEqualTo("Routes");
        assertThat(read.path("features").path(0).asText()).isEqualTo("bots");
        assertThat(call(HttpMethod.GET, "/admin/projects/" + id, memberToken, null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/admin/projects", adminToken, null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/admin/projects", adminToken, Map.of("name", "Nope"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode renamed = call(HttpMethod.PUT, "/admin/projects/" + id, adminToken, Map.of("name", "Renamed", "setting", new Object[0]), JsonNode.class).getBody();
        assertThat(renamed.path("name").asText()).isEqualTo("Renamed");

        JsonNode members = call(HttpMethod.GET, "/admin/projects/" + id + "/members", adminToken, null, JsonNode.class).getBody();
        assertThat(members).hasSize(2);
        String memberSeat = null;
        for (JsonNode seat : members) {
            if (seat.path("email").asText().equals(member)) {
                memberSeat = seat.path("id").asText();
            }
        }
        JsonNode promoted = call(HttpMethod.PUT, "/admin/projects/" + id + "/members/" + memberSeat, adminToken, Map.of("admin", true), JsonNode.class).getBody();
        assertThat(promoted.path("admin").asBoolean()).isTrue();
        assertThat(call(HttpMethod.GET, "/admin/projects/" + id, memberToken, null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Retry the identical request: the row becomes active, exactly one organisation and one partition exist, nothing was deleted. */
    private void assertResumes(String superAdmin, String name, Project anchor, int expectedOrganisations) {
        ResponseEntity<JsonNode> retried = call(HttpMethod.POST, "/admin/projects", superAdmin, Map.of("name", name), JsonNode.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody().path("id").asText()).as("the retry resumed the same project").isEqualTo(anchor.id().toString());
        assertThat(store.project(anchor.id()).orElseThrow().status()).isEqualTo("active");
        assertThat(organisations()).isEqualTo(expectedOrganisations);
        assertThat(keycloak.realm(TenantConfig.REALM).organizations().search(anchor.id().toString(), true, 0, 5)).hasSize(1);
        assertThat(partitions.listPartitions().stream().filter(partition -> partition.getName().equals(anchor.id().toString()))).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM bigbook.project WHERE name = :name").param("name", name).query(Long.class).single()).isEqualTo(1);
    }

    private int organisations() {
        return (int) keycloak.realm(TenantConfig.REALM).organizations().count(null);
    }
}
