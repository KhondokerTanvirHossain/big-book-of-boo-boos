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
