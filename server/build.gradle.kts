import java.util.Properties
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot")
}

val versions = extra["versions"] as Properties

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("ca.uhn.hapi.fhir:hapi-fhir-bom:${versions.getProperty("HAPI_FHIR_VERSION")}"))

    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-jpaserver-base")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Testcontainers 2.x names: HAPI's BOM pins the 2.x line, and mixing it with Boot's 1.x modules does not link
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName = "bigbook-server.jar"
}

tasks.jar {
    enabled = false
}

tasks.test {
    // the integration test runs against the same Postgres the lite stack pins
    systemProperty("bigbook.test.postgres-image", "postgres:${versions.getProperty("POSTGRES_VERSION")}")
    systemProperty("bigbook.test.keycloak-image", "quay.io/keycloak/keycloak:${versions.getProperty("KEYCLOAK_VERSION")}")
    systemProperty("bigbook.repo-root", rootDir.absolutePath)
}
