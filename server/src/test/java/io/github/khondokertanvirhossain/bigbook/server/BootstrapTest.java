package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantProvisioner;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/** Issue #3's acceptance criteria, against the pinned Keycloak and Postgres. Bootstrap ran at context start. */
class BootstrapTest extends LiteStackTest {

    @Autowired
    BootstrapRunner bootstrap;

    @Autowired
    IPartitionLookupSvc partitions;

    @Test
    void firstStartCreatesTheSuperAdminProjectInAllThreeStores() {
        // other suites create projects in the same stack; bootstrap's are the super-admin project and its one seat
        Map<String, Object> project = jdbc.sql("SELECT * FROM bigbook.project WHERE super_admin").query().singleRow();
        Map<String, Object> membership = jdbc.sql("SELECT * FROM bigbook.project_membership WHERE project_id = :project")
                .param("project", project.get("id")).query().singleRow();
        String projectId = project.get("id").toString();
        RealmResource realm = keycloak.realm(TenantConfig.REALM);
        OrganizationRepresentation organization = realm.organizations().search(projectId, true, 0, 2).get(0);
        UserRepresentation user = realm.users().searchByEmail(ADMIN_EMAIL, true).get(0);

        assertThat(project).containsEntry("super_admin", true).containsEntry("status", "active");
        assertThat(membership)
                .containsEntry("email", ADMIN_EMAIL)
                .containsEntry("admin", true)
                .containsEntry("status", "active")
                .containsEntry("user_id", user.getId());
        assertThat(organization.getAlias()).isEqualTo(projectId);
        assertThat(realm.organizations().get(organization.getId()).members().list(0, 10))
                .extracting(member -> member.getId())
                .containsExactly(user.getId());
        PartitionEntity partition = partitions.getPartitionByName(projectId);
        assertThat(partition.getId()).isEqualTo(project.get("partition_id"));
    }

    @Test
    void startingAgainChangesNothing() throws Exception {
        String before = snapshot();

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(snapshot()).isEqualTo(before);
    }

    /** ADR-007: a start that died after the anchor rows were written resumes, and creates nothing twice. */
    @Test
    void aHalfFinishedBootstrapResumesWithoutDuplicates() throws Exception {
        String before = snapshot();
        jdbc.sql("UPDATE bigbook.project SET status = 'provisioning' WHERE super_admin").update();
        jdbc.sql("""
                UPDATE bigbook.project_membership SET status = 'provisioning', user_id = NULL
                WHERE project_id = (SELECT id FROM bigbook.project WHERE super_admin)""").update();

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void theEnvAdminLogsInAndTheTokenCarriesTheSuperAdminRole() throws Exception {
        String payload = token(ADMIN_EMAIL, ADMIN_PASSWORD, "openid").split("\\.")[1];
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));

        assertThat(claims.path("email").asText()).isEqualTo(ADMIN_EMAIL);
        assertThat(claims.path("realm_access").path("roles"))
                .extracting(JsonNode::asText)
                .contains(TenantProvisioner.SUPER_ADMIN_ROLE);
    }

    /** Everything bootstrap writes, in all three stores, ids included. */
    private String snapshot() {
        RealmResource realm = keycloak.realm(TenantConfig.REALM);
        List<String> lines = new ArrayList<>();
        jdbc.sql("SELECT * FROM bigbook.project ORDER BY id").query().listOfRows().forEach(row -> lines.add("project " + row));
        jdbc.sql("SELECT * FROM bigbook.project_membership ORDER BY id").query().listOfRows().forEach(row -> lines.add("membership " + row));
        for (OrganizationRepresentation organization : realm.organizations().getAll()) {
            lines.add("organisation " + organization.getId() + " " + organization.getAlias());
            realm.organizations().get(organization.getId()).members().list(0, 100)
                    .forEach(member -> lines.add("  member " + member.getId()));
        }
        for (UserRepresentation user : realm.users().list()) {
            lines.add("user " + user.getId() + " " + user.getUsername() + " roles "
                    + realm.users().get(user.getId()).roles().realmLevel().listAll().stream().map(role -> role.getName()).sorted().toList());
        }
        partitions.listPartitions().forEach(partition -> lines.add("partition " + partition.getId() + " " + partition.getName()));
        return String.join("\n", lines);
    }
}
