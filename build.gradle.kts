import java.util.Properties

// deploy/versions.env is the single source for upstream versions (BB-R-011.6)
val versions = Properties().apply {
    file("deploy/versions.env").inputStream().use { load(it) }
}

allprojects {
    group = "io.github.khondokertanvirhossain.bigbook"
    version = versions.getProperty("BIGBOOK_VERSION")
    extra["versions"] = versions
}

subprojects {
    repositories {
        mavenCentral()
    }

    // Spring logs through spring-jcl. HAPI and RESTEasy each drag in another commons-logging implementation;
    // with those on the classpath Spring's own messages, startup failures included, go missing.
    configurations.configureEach {
        exclude(group = "commons-logging", module = "commons-logging")
        exclude(group = "org.jboss.logging", module = "commons-logging-jboss-logging")
        // RESTEasy (Keycloak admin client) brings org.jboss:jandex 2.x; Hibernate needs io.smallrye:jandex 3.x.
        // Same classes under two coordinates, so Gradle sees no conflict, and in the boot jar 2.x wins by name.
        exclude(group = "org.jboss", module = "jandex")
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
