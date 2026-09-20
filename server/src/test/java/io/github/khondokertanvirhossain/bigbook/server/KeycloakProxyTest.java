package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * Issue #5: Keycloak's login pages on Big Book's origin, and — the point of an allow-list — that
 * everything else on Keycloak's origin is refused here.
 */
class KeycloakProxyTest extends LiteStackTest {

    @Autowired
    KeycloakProxyController proxy;

    @Test
    void theRealmsLoginPagesAndThemeAssetsAreServed() {
        String redirect = "http://localhost/cb";
        ClientRepresentation browserClient = new ClientRepresentation();
        browserClient.setClientId("proxy-test");
        browserClient.setPublicClient(true);
        browserClient.setStandardFlowEnabled(true);
        browserClient.setRedirectUris(List.of(redirect));
        keycloak.realm(TenantConfig.REALM).clients().create(browserClient).close(); // 409 after the first run

        ResponseEntity<String> authorize = call(HttpMethod.GET,
                "/realms/bigbook/protocol/openid-connect/auth?client_id=proxy-test"
                        + "&response_type=code&redirect_uri=http%3A%2F%2Flocalhost%2Fcb&scope=openid&state=s",
                null, null, String.class);

        assertThat(authorize.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorize.getBody()).contains("<form").contains("username");
        // the form posts back to this origin, which is the whole reason the proxy exists
        assertThat(authorize.getBody()).contains("/realms/bigbook/login-actions/authenticate");
        assertThat(authorize.getHeaders().get("Set-Cookie")).isNotEmpty();

        // the theme assets the page itself links to: /resources/<build hash>/..., so take one from the page
        java.util.regex.Matcher asset = java.util.regex.Pattern
                .compile("href=\"(/resources/[^\"]+\\.css)\"")
                .matcher(authorize.getBody());
        assertThat(asset.find()).as("the login page links a stylesheet under /resources/").isTrue();
        ResponseEntity<String> theme = call(HttpMethod.GET, asset.group(1), null, null, String.class);
        assertThat(theme.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theme.getHeaders().getContentType().toString()).startsWith("text/css");
    }

    @Test
    void theRealmsOidcEndpointsAndTheAccountConsoleAreInsideTheAllowList() {
        ResponseEntity<JsonNode> discovery = call(HttpMethod.GET, "/realms/bigbook/.well-known/openid-configuration", null, null, JsonNode.class);
        ResponseEntity<String> jwks = call(HttpMethod.GET, "/realms/bigbook/protocol/openid-connect/certs", null, null, String.class);
        // a browser's Accept: Keycloak answers the account console 401 to Accept: application/json and
        // 200 to text/html (measured, issue #5), and the proxy passes both through unchanged
        ResponseEntity<String> account = call(HttpMethod.GET, "/realms/bigbook/account/", null, null, String.class,
                "Accept", "text/html");

        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discovery.getBody().path("issuer").asText()).endsWith("/realms/bigbook");
        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        // user-scoped, not admin: it is inside the allow-list and must stay reachable
        assertThat(account.getStatusCode().value()).isIn(200, 302);
        assertThat(account.getBody()).contains("<html");
    }

    /** An allow-list that is not tested against what it excludes is a deny-list with extra steps. */
    @Test
    void everythingElseOnKeycloaksOriginIs404Here() {
        long proxiedBefore = proxy.proxiedCount();

        for (String refused : List.of(
                "/admin/",
                "/admin/master/console/",
                "/admin/realms/bigbook/users",
                "/realms/master/",
                "/realms/master/.well-known/openid-configuration",
                "/realms/master/protocol/openid-connect/token",
                "/metrics",
                "/health",
                "/health/ready")) {
            assertThat(call(HttpMethod.GET, refused, null, null, String.class).getStatusCode())
                    .as(refused)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        assertThat(proxy.proxiedCount())
                .as("an excluded path must never be forwarded to Keycloak")
                .isEqualTo(proxiedBefore);
    }

    /**
     * …including anything that tries to reach an excluded path through an allowed prefix.
     *
     * <p><b>Do not "fix" the status codes here.</b> The property under test is that the request is never
     * proxied, asserted on the proxy's own counter. The status varies by which layer rejects it and that is
     * correct: Tomcat normalises {@code /realms/../admin/...} to {@code /admin/...} per the servlet spec
     * before any filter sees it, so the request dies in the security chain as 401; a form Tomcat rejects
     * outright is 400; a form that reaches {@link KeycloakOriginNotHereFilter} is 404. Forcing one status
     * would need a container valve or a raw-URI comparison — machinery bought for a status code, with no
     * requirement behind it (decided 2026-09-19).
     */
    @Test
    void traversalAndEncodedVariantsDoNotReachKeycloak() {
        long proxiedBefore = proxy.proxiedCount();

        for (String attempt : List.of(
                "/realms/../admin/master/console/",
                "/realms/bigbook/../../admin/realms/bigbook/users",
                "/realms/%2e%2e/admin/master/console/",
                "/realms/bigbook/..%2f..%2fadmin/realms/bigbook/users",
                "/realms/..%252fadmin/master/console/",
                "/resources/../admin/master/console/",
                "/realms/bigbook/protocol/../../../admin/realms/bigbook/users")) {
            ResponseEntity<String> response = call(HttpMethod.GET, attempt, null, null, String.class);

            assertThat(response.getStatusCode().value()).as(attempt).isIn(400, 401, 404);
            assertThat(response.getBody() == null ? "" : response.getBody().toLowerCase())
                    .as(attempt)
                    .doesNotContain("keycloak")
                    .doesNotContain("administration console");
        }

        assertThat(proxy.proxiedCount())
                .as("not one of those requests may be forwarded to Keycloak")
                .isEqualTo(proxiedBefore);
    }

    @Test
    void bigBooksOwnAdminRestCallsDoNotUseTheProxy() {
        // the proxy would answer 404 for /admin/realms/**, so bootstrap working at all proves the
        // internal address is in use; assert the configured URL is not Big Book's own origin
        assertThat(keycloakUrl()).doesNotContain("localhost:" + System.getProperty("local.server.port", "-1"));
        assertThat(store.superAdminProject()).isPresent();
    }
}
