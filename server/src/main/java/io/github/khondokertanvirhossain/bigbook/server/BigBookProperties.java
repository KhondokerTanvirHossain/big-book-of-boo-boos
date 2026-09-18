package io.github.khondokertanvirhossain.bigbook.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Big Book's own configuration keys. Every property here needs a row in
 * docs/guides/config.md; ConfigDocTest fails the build otherwise (BB-R-011.3).
 *
 * @param baseUrl Public origin of this server, with trailing slash. Base of every link the FHIR API emits.
 */
@ConfigurationProperties("bigbook")
public record BigBookProperties(@DefaultValue("http://localhost:8080/") String baseUrl) {

    /** Base URL of the FHIR API, Medplum's path: {@code <base-url>fhir/R4}. */
    public String fhirBaseUrl() {
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + FhirServerConfig.FHIR_PATH.substring(1);
    }
}
