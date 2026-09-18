package io.github.khondokertanvirhossain.bigbook.server;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
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
        registry.add("bigbook.base-url", () -> "http://bigbook.test/");
        registry.add("bigbook.admin.email", () -> ADMIN_EMAIL);
        registry.add("bigbook.admin.password", () -> ADMIN_PASSWORD);
        registry.add("bigbook.keycloak.url", LiteStackTest::keycloakUrl);
        registry.add("bigbook.keycloak.client-secret", () -> CLIENT_SECRET);
    }
}
