package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Medplum's OAuth path spellings over Keycloak (BB-R-004.8, ADR-003 option B). Three shapes:
 *
 * <ul>
 *   <li><b>redirect</b> — {@code /oauth2/authorize} 302s to Keycloak's authorization endpoint, which is
 *       on this same origin via {@link KeycloakProxyController}, so the browser stays put (ARCHITECTURE §2(g)).
 *   <li><b>passthrough</b> — {@code /oauth2/token} and {@code /oauth2/userinfo} are forwarded verbatim in
 *       v0.1; the response wrapper that adds {@code project} and {@code profile} to the token body is v0.2 (D3/D51).
 *   <li><b>re-shaped</b> — {@code /.well-known/openid-configuration} advertises Big Book's paths, and
 *       {@code /oauth2/logout} takes a bearer token rather than OIDC's front-channel parameters.
 * </ul>
 */
@RestController
public class OAuthController {

    private static final String OIDC = "/realms/" + TenantConfig.REALM + "/protocol/openid-connect";

    private final BigBookProperties properties;
    private final RestClient keycloak;
    private final Keycloak admin;
    private final ObjectMapper json;

    public OAuthController(BigBookProperties properties, Keycloak admin, ObjectMapper json) {
        this.properties = properties;
        this.admin = admin;
        this.json = json;
        this.keycloak = RestClient.create(properties.keycloak().url());
    }

    /** Medplum's `/oauth2/authorize`: Keycloak renders every page, so this is a redirect, not a proxy. */
    @GetMapping("/oauth2/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base() + OIDC.substring(1) + "/auth" + query))
                .build();
    }

    /** Proxied verbatim in v0.1 (D3): the SDK's `project`/`profile` body fields are v0.2. */
    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> token(@RequestBody String form) {
        return keycloak.post()
                .uri(OIDC + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    @RequestMapping("/oauth2/userinfo")
    public ResponseEntity<String> userinfo(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return keycloak.get()
                .uri(OIDC + "/userinfo")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    /** Keycloak's own JWKS, at Medplum's path. */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<String> jwks() {
        return keycloak.get()
                .uri(OIDC + "/certs")
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    /**
     * Re-shaped: the endpoints are Big Book's spellings, the issuer is Keycloak's own (`iss` must match
     * what tokens carry — {@code <base-url>realms/bigbook}, ADR-003), and the rest is passed through.
     */
    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<JsonNode> discovery() throws Exception {
        ObjectNode document = (ObjectNode) json.readTree(keycloak.get()
                .uri("/realms/" + TenantConfig.REALM + "/.well-known/openid-configuration")
                .retrieve()
                .body(String.class));
        document.put("authorization_endpoint", base() + "oauth2/authorize");
        document.put("token_endpoint", base() + "oauth2/token");
        document.put("userinfo_endpoint", base() + "oauth2/userinfo");
        document.put("end_session_endpoint", base() + "oauth2/logout");
        document.put("jwks_uri", base() + ".well-known/jwks.json");
        // the issuer is not re-shaped: it is what every token's `iss` says, and they must agree
        return ResponseEntity.ok(document);
    }

    /**
     * Re-shaped (ADR-003): a bearer token, not OIDC's front-channel `id_token_hint` + redirect. The
     * session behind the token is deleted through Keycloak's Admin REST, so the caller needs no browser.
     */
    @PostMapping("/oauth2/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt token) {
        String sessionId = token.getClaimAsString("sid");
        if (sessionId != null) {
            admin.realm(TenantConfig.REALM).deleteSession(sessionId, false);
        }
        return ResponseEntity.noContent().build();
    }

    private String base() {
        String base = properties.baseUrl();
        return base.endsWith("/") ? base : base + "/";
    }

    /** Medplum's `Login`-protocol routes are a non-goal (BB-R-004.8a); they answer 404, documented. */
    @RequestMapping({"/auth/login", "/auth/newuser", "/auth/newproject", "/auth/newpatient", "/auth/method",
            "/auth/changepassword", "/auth/resetpassword", "/auth/setpassword", "/auth/verifyemail",
            "/auth/google", "/auth/external", "/auth/exchange", "/auth/profile", "/auth/scope"})
    public ResponseEntity<Map<String, String>> loginProtocolIsNotAGoal() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("resourceType", "OperationOutcome", "id", "not-found"));
    }
}
