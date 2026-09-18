package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.TenantProvisioner;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Issue #3's acceptance criteria, against the pinned Keycloak and Postgres. Bootstrap ran at context start. */
class BootstrapTest extends LiteStackTest {

    @Autowired
    BootstrapRunner bootstrap;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Keycloak keycloak;

    @Autowired
    IPartitionLookupSvc partitions;

    @Test
    void firstStartCreatesTheSuperAdminProjectInAllThreeStores() {
        Map<String, Object> project = jdbc.sql("SELECT * FROM bigbook.project").query().singleRow();
        Map<String, Object> membership = jdbc.sql("SELECT * FROM bigbook.project_membership").query().singleRow();
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
        jdbc.sql("UPDATE bigbook.project SET status = 'provisioning'").update();
        jdbc.sql("UPDATE bigbook.project_membership SET status = 'provisioning', user_id = NULL").update();

        bootstrap.run(new DefaultApplicationArguments());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void theEnvAdminLogsInAndTheTokenCarriesTheSuperAdminRole() throws Exception {
        // a throwaway public client: which clients the realm ships is issue #5's decision, and Keycloak's
        // built-in admin-cli issues lightweight tokens that carry no roles
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("bootstrap-test");
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        keycloak.realm(TenantConfig.REALM).clients().create(client).close();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "bootstrap-test");
        form.add("username", ADMIN_EMAIL);
        form.add("password", ADMIN_PASSWORD);

        JsonNode token = RestClient.create()
                .post()
                .uri(keycloakUrl() + "/realms/bigbook/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String payload = token.path("access_token").asText().split("\\.")[1];
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
