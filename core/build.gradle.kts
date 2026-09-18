import java.util.Properties

plugins {
    `java-library`
}

val versions = extra["versions"] as Properties

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${versions.getProperty("SPRING_BOOT_VERSION")}"))
    api(platform("ca.uhn.hapi.fhir:hapi-fhir-bom:${versions.getProperty("HAPI_FHIR_VERSION")}"))

    api("org.keycloak:keycloak-admin-client:${versions.getProperty("KEYCLOAK_ADMIN_CLIENT_VERSION")}")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-jpaserver-base")
    implementation("org.springframework:spring-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
