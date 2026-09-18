package io.github.khondokertanvirhossain.bigbook.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Big Book's own configuration keys. Every property here needs a row in
 * docs/guides/config.md; ConfigDocTest fails the build otherwise (BB-R-011.3).
 *
 * @param baseUrl Public origin of this server, with trailing slash. Base of every link the FHIR API emits.
 * @param admin The super-admin created on first boot.
 * @param keycloak How the server reaches Keycloak's Admin REST API.
 */
@ConfigurationProperties("bigbook")
public record BigBookProperties(
        @DefaultValue("http://localhost:8080/") String baseUrl,
        @DefaultValue Admin admin,
        @DefaultValue Keycloak keycloak) {

    /**
     * @param email Email of the super-admin user. Required; there is no default.
     * @param password Password of the super-admin user. When unset, one is generated and logged once.
     */
    public record Admin(String email, String password) {}

    /**
     * @param url Base URL of Keycloak as the server reaches it.
     * @param clientId Confidential client, defined in the realm import, whose service account administers the realm.
     * @param clientSecret Secret of that client. No default.
     */
    public record Keycloak(
            @DefaultValue("http://keycloak:8080") String url,
            @DefaultValue("bigbook-server") String clientId,
            String clientSecret) {}

    /** Base URL of the FHIR API, Medplum's path: {@code <base-url>fhir/R4}. */
    public String fhirBaseUrl() {
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + FhirServerConfig.FHIR_PATH.substring(1);
    }
}
