rootProject.name = "boss-plugin-llmrpa"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Include BossConsole for plugin-api dependency (composite build)
// Try multiple locations: CI environment (../BossConsole) or local development (../../BossConsole)
val bossConsolePaths = listOf(
    "../BossConsole",      // CI: checked out alongside plugin
    "../../BossConsole"    // Local: relative to boss_plugin directory
)

val bossConsoleDir = bossConsolePaths
    .map { File(rootDir, it) }
    .firstOrNull { it.exists() && it.isDirectory }

if (bossConsoleDir != null) {
    includeBuild(bossConsoleDir.absolutePath) {
        dependencySubstitution {
            substitute(module("ai.rever.boss.plugin:plugin-api-desktop")).using(project(":plugins:plugin-api"))
            substitute(module("ai.rever.boss.plugin:plugin-api-browser-desktop")).using(project(":plugins:plugin-api-browser"))
            substitute(module("ai.rever.boss.plugin:plugin-scrollbar-desktop")).using(project(":plugins:plugin-scrollbar"))
            substitute(module("ai.rever.boss.plugin:plugin-logging-desktop")).using(project(":plugins:plugin-logging"))
            substitute(module("ai.rever.boss.plugin:plugin-ui-core-desktop")).using(project(":plugins:plugin-ui-core"))
        }
    }
} else {
    throw GradleException("BossConsole not found. Tried paths: ${bossConsolePaths.joinToString()}")
}
