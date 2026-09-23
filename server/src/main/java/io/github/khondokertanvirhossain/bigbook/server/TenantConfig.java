package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import io.github.khondokertanvirhossain.bigbook.core.tenant.KeycloakDirectory;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantProvisioner;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
import java.util.concurrent.TimeUnit;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
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
                // a Keycloak that accepts the connection and then says nothing must fail the request, not hang it
                .resteasyClient(new ResteasyClientBuilderImpl()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build())
                .build();
    }

    @Bean
    public TenantStore tenantStore(JdbcClient jdbc) {
        return new TenantStore(jdbc);
    }

    @Bean
    public TenantProvisioner tenantProvisioner(TenantStore store, Keycloak keycloak, IPartitionLookupSvc partitions,
            org.springframework.beans.factory.ObjectProvider<ca.uhn.fhir.jpa.api.dao.DaoRegistry> daoRegistry) {
        // lazily: the DaoRegistry is built by HAPI's own configuration, which is not ready when this bean is
        return new TenantProvisioner(store, new KeycloakDirectory(keycloak.realm(REALM)), partitions,
                (project, request, email) -> new HapiProfileResources(daoRegistry.getObject())
                        .ensureProfile(project, request, email));
    }

    @Bean
    public BootstrapRunner bootstrapRunner(BigBookProperties properties, TenantProvisioner provisioner) {
        return new BootstrapRunner(properties.admin(), provisioner);
    }
}
