package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.khondokertanvirhossain.bigbook.core.tenant.KeycloakDirectory;
import io.github.khondokertanvirhossain.bigbook.core.tenant.TenantStore;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The server against what lite.yml gives it: the pinned Postgres laid out the same way, and the pinned
 * Keycloak importing the real deploy/keycloak/bigbook-realm.json. Started once for the whole test run;
 * every subclass shares one Spring context, so bootstrap has already run when a test starts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class LiteStackTest {

    /** What Big Book's public URL is in tests; Keycloak is pinned to it, as in lite.yml. */
    static final String BASE_URL = "http://bigbook.test/";

    static final String ADMIN_EMAIL = "root@bigbook.test";
    static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String CLIENT_SECRET = UUID.randomUUID().toString();

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse(System.getProperty("bigbook.test.postgres-image"))
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("bigbook")
            .withUsername("bigbook")
            .withUrlParam("currentSchema", "hapi")
            .withInitScript("create-hapi-schema.sql");

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(System.getProperty("bigbook.test.keycloak-image"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("BIGBOOK_KEYCLOAK_CLIENT_SECRET", CLIENT_SECRET)
            // as deploy/compose/lite.yml runs it: the hostname is Big Book's origin and Keycloak trusts
            // the proxy's forwarded headers, which is what makes its own pages work behind the proxy
            .withEnv("KC_PROXY_HEADERS", "xforwarded")
            .withEnv("KC_HOSTNAME_STRICT", "false")
            // as lite.yml does: Keycloak's hostname is Big Book's public URL, so the issuer it stamps into
            // tokens is <BASE_URL>realms/bigbook — the string #5 validates and discovery advertises
            .withEnv("KC_HOSTNAME", BASE_URL)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of(System.getProperty("bigbook.repo-root"), "deploy/keycloak/bigbook-realm.json")),
                    "/opt/keycloak/data/import/bigbook-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/bigbook").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        Startables.deepStart(POSTGRES, KEYCLOAK).join();
    }

    static String keycloakUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    @DynamicPropertySource
    static void liteStack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("bigbook.base-url", () -> BASE_URL);
        registry.add("bigbook.admin.email", () -> ADMIN_EMAIL);
        registry.add("bigbook.admin.password", () -> ADMIN_PASSWORD);
        registry.add("bigbook.keycloak.url", LiteStackTest::keycloakUrl);
        registry.add("bigbook.keycloak.client-secret", () -> CLIENT_SECRET);
    }

    static final String TEST_CLIENT = "bigbook-tests";
    static final String USER_PASSWORD = UUID.randomUUID().toString();

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected Keycloak keycloak;

    @Autowired
    protected TenantStore store;

    @Autowired
    protected JdbcClient jdbc;

    /**
     * A token straight from Keycloak, as issue #4's criteria say: password grant on a throwaway public
     * client (which clients the realm ships is issue #5's), bound to a project by {@code organization:<alias>}.
     */
    protected String token(String email, String password, String scope) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(TEST_CLIENT);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        keycloak.realm(TenantConfig.REALM).clients().create(client).close(); // 409 after the first time
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", TEST_CLIENT);
        form.add("username", email);
        form.add("password", password);
        form.add("scope", scope);
        return RestClient.create().post()
                .uri(keycloakUrl() + "/realms/bigbook/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class)
                .path("access_token").asText();
    }

    protected String tokenFor(String email, UUID projectId) {
        return token(email, USER_PASSWORD, "openid organization:" + projectId);
    }

    protected String superAdminToken() {
        return token(ADMIN_EMAIL, ADMIN_PASSWORD, "openid organization:" + store.superAdminProject().orElseThrow().id());
    }

    /** A Keycloak user with {@link #USER_PASSWORD}; returns its id, the token {@code sub}. */
    protected String seedUser(String email) {
        return new KeycloakDirectory(keycloak.realm(TenantConfig.REALM)).ensureUser(email, "Test", "User", USER_PASSWORD, () -> {});
    }

    /** Issue #4: memberships seeded directly. The invite route that will do this is issue #6. */
    protected void seedSeat(UUID projectId, String email, String userId, boolean admin) {
        KeycloakDirectory directory = new KeycloakDirectory(keycloak.realm(TenantConfig.REALM));
        directory.ensureOrganizationMember(directory.organizationId(projectId.toString()), userId);
        store.insertProvisioningMembership(projectId, email, admin);
        store.markMembershipActive(projectId, email, userId);
    }

    protected <T> ResponseEntity<T> call(HttpMethod method, String url, String bearer, Object body, Class<T> type, String... headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (bearer != null) {
            httpHeaders.setBearerAuth(bearer);
        }
        if (body != null) {
            // FHIR resources are sent as strings; a map is a plain JSON payload (admin routes, $graphql)
            httpHeaders.setContentType(body instanceof String ? MediaType.valueOf("application/fhir+json") : MediaType.APPLICATION_JSON);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            httpHeaders.set(headers[i], headers[i + 1]);
        }
        // a URI, not a template: TestRestTemplate would otherwise re-encode %2F and %2e, which is exactly
        // what the allow-list tests need to send through untouched
        return http.exchange(URI.create(http.getRootUri() + url), method, new HttpEntity<>(body, httpHeaders), type);
    }
}
