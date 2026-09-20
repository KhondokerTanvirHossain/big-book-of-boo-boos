package io.github.khondokertanvirhossain.bigbook.server;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Keycloak's login pages on Big Book's origin (ADR-003, amended 2026-09-19). Keycloak's {@code hostname}
 * is Big Book's public URL, so every URL it emits — the login form's {@code action}, its redirects, its
 * CSS and JS — points here, and something must serve those two prefixes.
 *
 * <p><b>Allow-list, and only these two:</b> {@code /realms/<realm>/**} with the realm fixed from config,
 * and {@code /resources/**}. Everything else on Keycloak's origin is 404 here, including {@code /admin/**},
 * {@code /realms/master/**}, {@code /metrics} and {@code /health}: Spring maps only these two paths, so an
 * unlisted one never reaches this class. Big Book's own Admin REST calls use the internal container
 * address ({@code bigbook.keycloak.url}), never this proxy.
 */
@RestController
public class KeycloakProxyController {

    /** Hop-by-hop headers, which a proxy must not forward (RFC 9110 §7.6.1). */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final RestClient keycloak;
    private final java.util.concurrent.atomic.AtomicLong proxied = new java.util.concurrent.atomic.AtomicLong();

    public KeycloakProxyController(BigBookProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.keycloak = RestClient.builder()
                .baseUrl(properties.keycloak().url())
                .requestFactory(factory)
                .build();
    }

    /** The realm's own pages: login, the account console, and the OIDC endpoints Keycloak serves itself. */
    @RequestMapping("/realms/" + TenantConfig.REALM + "/**")
    public ResponseEntity<StreamingResponseBody> realm(HttpServletRequest request) throws IOException {
        return pass(request);
    }

    /** Themes: CSS, JS, fonts, images. Static, and public on Keycloak too. */
    @RequestMapping("/resources/**")
    public ResponseEntity<StreamingResponseBody> resources(HttpServletRequest request) throws IOException {
        return pass(request);
    }

    /** A 302 is the answer, not something to chase, so redirects are never followed: see {@link #copy}. */
    /** How many requests this proxy has forwarded to Keycloak. The allow-list tests assert it does not move. */
    long proxiedCount() {
        return proxied.get();
    }

    private ResponseEntity<StreamingResponseBody> pass(HttpServletRequest request) throws IOException {
        proxied.incrementAndGet();
        // getRequestURI() is already normalised and decoded by the container, so ../ and %2e%2e cannot
        // smuggle a path past the mapping: the request would not have matched it in the first place
        String path = request.getRequestURI();
        URI target = URI.create(path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));

        RestClient.RequestBodySpec spec = keycloak
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(headers -> forwardRequestHeaders(request, headers));
        if (request.getContentLengthLong() != 0) {
            byte[] body = request.getInputStream().readAllBytes();
            spec = spec.body(body).header(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        }
        return spec.exchange((outgoing, response) -> copy(response), false);
    }

    private void forwardRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                headers.addAll(name, List.copyOf(java.util.Collections.list(request.getHeaders(name))));
            }
        });
        // KC_PROXY_HEADERS=xforwarded: this is how Keycloak knows which origin to write into its pages
        headers.set("X-Forwarded-Host", request.getServerName() + (isDefaultPort(request) ? "" : ":" + request.getServerPort()));
        headers.set("X-Forwarded-Proto", request.getScheme());
        headers.set("X-Forwarded-Port", String.valueOf(request.getServerPort()));
    }

    private static boolean isDefaultPort(HttpServletRequest request) {
        return ("http".equals(request.getScheme()) && request.getServerPort() == 80)
                || ("https".equals(request.getScheme()) && request.getServerPort() == 443);
    }

    /** Status, headers and body through unchanged: {@code Set-Cookie} and {@code Location} included. */
    private static ResponseEntity<StreamingResponseBody> copy(ClientHttpResponse response) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        response.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                headers.addAll(name, values);
            }
        });
        byte[] body = response.getBody().readAllBytes();
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers)
                .contentType(headers.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : headers.getContentType())
                .body(out -> out.write(body));
    }
}
