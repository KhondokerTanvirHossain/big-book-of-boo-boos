package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.TenantStore;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * Bearer tokens from realm {@code bigbook}, validated by signature and expiry against the realm's keys
 * (issue #4). The keys are per realm, so a token from another realm fails here. The issuer string is not
 * checked yet: it becomes fixed when issue #5 pins Keycloak's hostname to Big Book's public URL (ADR-003).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(BigBookProperties properties) {
        // Fetched on first use and cached, so Keycloak need not be up when Big Book starts. With timeouts:
        // the default has none, and a Keycloak that accepts the connection and then says nothing would hang
        // every authenticated request whenever the key cache is cold. It fails as 401 instead.
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(5));
        http.setReadTimeout(Duration.ofSeconds(10));
        return NimbusJwtDecoder.withJwkSetUri(
                        properties.keycloak().url() + "/realms/" + TenantConfig.REALM + "/protocol/openid-connect/certs")
                .restOperations(new RestTemplate(http))
                .build();
    }

    @Bean
    public OperationOutcomes operationOutcomes(FhirContext fhirContext) {
        return new OperationOutcomes(fhirContext);
    }

    @Bean
    public SecurityFilterChain api(HttpSecurity http, TenantStore store, OperationOutcomes outcomes) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // health for the container, and the CapabilityStatement, which Medplum also serves openly
                        .requestMatchers(path.matcher("/actuator/health/**"), path.matcher(FhirServerConfig.FHIR_PATH + "/metadata"))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                // not a bean: Boot would also register it on the servlet container, outside the security chain
                .addFilterAfter(new ProjectContextFilter(store, outcomes), AuthorizationFilter.class)
                .addFilterAfter(new ConditionalUpdateIdFilter(outcomes, new ObjectMapper()), ProjectContextFilter.class)
                .build();
    }
}
