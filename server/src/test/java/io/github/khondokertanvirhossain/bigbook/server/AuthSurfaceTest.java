package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Issue #5: Medplum's OAuth paths over Keycloak, {@code /auth/me}, and issuer validation (BB-R-004.8/.9). */
class AuthSurfaceTest extends LiteStackTest {

    @Test
    void discoveryAdvertisesBigBooksPathsAndKeycloaksIssuer() {
        ResponseEntity<JsonNode> discovery = call(HttpMethod.GET, "/.well-known/openid-configuration", null, null, JsonNode.class);

        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode document = discovery.getBody();
        // the issuer is NOT re-shaped: it must equal what tokens carry (ADR-003)
        assertThat(document.path("issuer").asText()).isEqualTo("http://bigbook.test/realms/bigbook");
        assertThat(document.path("authorization_endpoint").asText()).isEqualTo("http://bigbook.test/oauth2/authorize");
        assertThat(document.path("token_endpoint").asText()).isEqualTo("http://bigbook.test/oauth2/token");
        assertThat(document.path("userinfo_endpoint").asText()).isEqualTo("http://bigbook.test/oauth2/userinfo");
        assertThat(document.path("end_session_endpoint").asText()).isEqualTo("http://bigbook.test/oauth2/logout");
        assertThat(document.path("jwks_uri").asText()).isEqualTo("http://bigbook.test/.well-known/jwks.json");
    }

    @Test
    void theDiscoveryIssuerIsTheIssuerOfARealToken() throws Exception {
        String issuer = call(HttpMethod.GET, "/.well-known/openid-configuration", null, null, JsonNode.class)
                .getBody().path("issuer").asText();

        JsonNode claims = claimsOf(superAdminToken());

        // "discovery issuer matches token iss" — the BB-R-014 exit test, and what #5's criterion requires
        assertThat(claims.path("iss").asText()).isEqualTo(issuer);
    }

    @Test
    void jwksIsServedAtMedplumsPath() {
        ResponseEntity<JsonNode> jwks = call(HttpMethod.GET, "/.well-known/jwks.json", null, null, JsonNode.class);

        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwks.getBody().path("keys")).isNotEmpty();
    }

    @Test
    void tokenIsProxiedAndCarriesTheClaimsTheSdkReads() throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", TEST_CLIENT);
        form.add("username", ADMIN_EMAIL);
        form.add("password", ADMIN_PASSWORD);
        form.add("scope", "openid organization:" + store.superAdminProject().orElseThrow().id());

        ResponseEntity<JsonNode> token = call(HttpMethod.POST, "/oauth2/token", null, form, JsonNode.class,
                "Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode claims = claimsOf(token.getBody().path("access_token").asText());
        assertThat(claims.path("exp").isNumber()).isTrue();
        // BB-R-004.9: `project` is Keycloak's organization claim, and every user token carries `sid`,
        // which is `login_id`. `client_id` is on client-credentials tokens (Keycloak's own basic scope).
        assertThat(claims.path("organization")).hasSize(1);
        assertThat(claims.path("sid").asText()).isNotBlank();
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong())
                .as("access-token lifespan >= 15 min (D42)")
                .isGreaterThanOrEqualTo(900);
    }

    @Test
    void authorizeRedirectsToKeycloaksOwnEndpointOnThisOrigin() {
        // TestRestTemplate follows redirects and http://bigbook.test does not resolve, so ask for the
        // 302 itself: the Location header is the contract, not whatever Keycloak would answer next
        ResponseEntity<Void> redirect = noFollow().exchange(
                java.net.URI.create(http.getRootUri()
                        + "/oauth2/authorize?client_id=x&response_type=code&redirect_uri=http%3A%2F%2Flocalhost%2Fcb&scope=openid"),
                HttpMethod.GET, org.springframework.http.HttpEntity.EMPTY, Void.class);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation().toString())
                .startsWith("http://bigbook.test/realms/bigbook/protocol/openid-connect/auth")
                .contains("client_id=x")
                .contains("scope=openid");
    }

    @Test
    void authMeReturnsTheProjectAndSeatBehindTheToken() {
        ResponseEntity<JsonNode> me = call(HttpMethod.GET, "/auth/me", superAdminToken(), null, JsonNode.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().path("project").path("name").asText()).isEqualTo("Super Admin");
        assertThat(me.getBody().path("project").path("superAdmin").asBoolean()).isTrue();
        assertThat(me.getBody().path("membership").path("admin").asBoolean()).isTrue();
        assertThat(me.getBody().path("membership").path("id").asText()).isNotBlank();
        // profile is null until the invite flow creates profile resources (issue #6)
        assertThat(me.getBody().path("profile").isNull()).isTrue();
        assertThat(call(HttpMethod.GET, "/auth/me", null, null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userinfoIsProxied() {
        ResponseEntity<JsonNode> userinfo = call(HttpMethod.GET, "/oauth2/userinfo", superAdminToken(), null, JsonNode.class);

        assertThat(userinfo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userinfo.getBody().path("email").asText()).isEqualTo(ADMIN_EMAIL);
    }

    /**
     * Issuer validation (#5). Keycloak is pinned to Big Book's URL, so there is no second issuer to borrow a
     * token from; the claim is edited instead, which also breaks the signature — so this proves the pair is
     * refused, not that `iss` alone is. That the issuer is checked on its own is proved directly against
     * {@code JwtValidators.createDefaultWithIssuer(SecurityConfig.issuer(...))}: right issuer 0 errors,
     * wrong issuer 1 (measured 2026-09-20). Both matter; neither test alone covers it.
     */
    @Test
    void aTokenWhoseIssuerIsNotBigBooksIsRejected() throws Exception {
        String valid = superAdminToken();
        String[] parts = valid.split("\\.");
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode claims =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        claims.put("iss", "http://attacker.invalid/realms/bigbook");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims))
                + "." + parts[2];

        assertThat(claimsOf(valid).path("iss").asText()).isEqualTo("http://bigbook.test/realms/bigbook");
        assertThat(call(HttpMethod.GET, "/fhir/R4/Patient", forged, null, String.class).getStatusCode())
                .as("a token whose iss is not Big Book's is refused")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theLoginProtocolRoutesAreDocumentedNonGoals() {
        for (String nonGoal : List.of("/auth/login", "/auth/newuser", "/auth/newproject", "/auth/setpassword", "/auth/google")) {
            assertThat(call(HttpMethod.POST, nonGoal, null, "{}", String.class).getStatusCode())
                    .as(nonGoal)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    /** A client that reports a 302 instead of chasing it. */
    private static org.springframework.web.client.RestTemplate noFollow() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                        super.prepareConnection(connection, method);
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        return new org.springframework.web.client.RestTemplate(factory);
    }

    private JsonNode claimsOf(String accessToken) throws Exception {
        String payload = accessToken.split("\\.")[1];
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));
    }
}
