pluginManagement {
    // deploy/versions.env is the single source for upstream versions (BB-R-011.6)
    val versions = java.util.Properties().apply {
        java.io.File(rootDir, "deploy/versions.env").inputStream().use { load(it) }
    }
    plugins {
        id("org.springframework.boot") version versions.getProperty("SPRING_BOOT_VERSION")
    }
}

rootProject.name = "big-book"

include("server")
