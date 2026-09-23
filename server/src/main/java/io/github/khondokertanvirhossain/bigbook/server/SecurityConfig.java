package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * Bearer tokens from realm {@code bigbook}: signature, expiry, and — since Keycloak's {@code hostname} is
 * Big Book's public URL (ADR-003, issue #5) — the issuer, which must be
 * {@code <BIGBOOK_BASE_URL>realms/bigbook}, the same string the discovery document advertises.
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
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                        properties.keycloak().url() + "/realms/" + TenantConfig.REALM + "/protocol/openid-connect/certs")
                .restOperations(new RestTemplate(http))
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer(properties)));
        return decoder;
    }

    /** Keycloak appends {@code /realms/<realm>} to its hostname, so this is what a token's {@code iss} is. */
    static String issuer(BigBookProperties properties) {
        String base = properties.baseUrl();
        return (base.endsWith("/") ? base : base + "/") + "realms/" + TenantConfig.REALM;
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
                        // Keycloak's own login pages and theme assets: a browser reaches them before it has
                        // any token, and Keycloak authenticates them itself (ADR-003 allow-list, issue #5)
                        .requestMatchers(
                                path.matcher("/realms/" + TenantConfig.REALM + "/**"),
                                path.matcher("/resources/**"))
                        .permitAll()
                        // OAuth endpoints Keycloak authenticates itself, and the documented non-goals
                        .requestMatchers(
                                path.matcher("/oauth2/authorize"),
                                path.matcher("/oauth2/token"),
                                path.matcher("/.well-known/**"),
                                path.matcher("/auth/login"),
                                path.matcher("/auth/newuser"),
                                path.matcher("/auth/newproject"),
                                path.matcher("/auth/newpatient"),
                                path.matcher("/auth/method"),
                                path.matcher("/auth/changepassword"),
                                path.matcher("/auth/resetpassword"),
                                path.matcher("/auth/setpassword"),
                                path.matcher("/auth/verifyemail"),
                                path.matcher("/auth/google"),
                                path.matcher("/auth/external"),
                                path.matcher("/auth/exchange"),
                                path.matcher("/auth/profile"),
                                path.matcher("/auth/scope"))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                // not a bean: Boot would also register it on the servlet container, outside the security chain
                .addFilterAfter(new ProjectContextFilter(store, outcomes), AuthorizationFilter.class)
                .addFilterAfter(new ConditionalUpdateIdFilter(outcomes, new ObjectMapper()), ProjectContextFilter.class)
                // Keycloak's non-allow-listed paths are not Big Book's paths: 404 before the chain can
                // ask for a token, so an operator who reaches /admin/ here sees "wrong address", not
                // "authenticate" (ADR-003 allow-list, issue #5)
                .addFilterBefore(new KeycloakOriginNotHereFilter(outcomes), org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .build();
    }
}
