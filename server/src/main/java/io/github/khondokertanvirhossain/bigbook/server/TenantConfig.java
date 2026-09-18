package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import io.github.khondokertanvirhossain.bigbook.core.KeycloakDirectory;
import io.github.khondokertanvirhossain.bigbook.core.TenantProvisioner;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires core/'s tenant provisioning to this server's Keycloak, database and embedded HAPI. */
@Configuration
public class TenantConfig {

    /** The realm is fixed; it is created by Keycloak's own import of deploy/keycloak/bigbook-realm.json. */
    public static final String REALM = "bigbook";

    @Bean(destroyMethod = "close")
    public Keycloak keycloakAdminClient(BigBookProperties properties) {
        return KeycloakBuilder.builder()
                .serverUrl(properties.keycloak().url())
                .realm(REALM)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.keycloak().clientId())
                .clientSecret(properties.keycloak().clientSecret())
                .build();
    }

    @Bean
    public TenantProvisioner tenantProvisioner(JdbcClient jdbc, Keycloak keycloak, IPartitionLookupSvc partitions) {
        return new TenantProvisioner(jdbc, new KeycloakDirectory(keycloak.realm(REALM)), partitions);
    }

    @Bean
    public BootstrapRunner bootstrapRunner(BigBookProperties properties, TenantProvisioner provisioner) {
        return new BootstrapRunner(properties.admin(), provisioner);
    }
}
