import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.1.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

// Newest boss-plugin-api jar from the sibling repo (local dev), resolved lazily in a provider
// so it runs at dependency-RESOLUTION time, not configuration time: clean/help/tasks still work
// on a fresh checkout without the sibling jar built, and compilation fails with this actionable
// message instead of unresolved-reference noise.
//
// This replaces a hardcoded `boss-plugin-api-1.0.51.jar`. That file no longer existed in the
// sibling checkout, and `compileOnly(files(...))` on a missing path contributes *nothing*
// silently — so every api symbol came back "unresolved reference" with no hint that the cause
// was a stale filename. Never name a version here.
val newestApiJar = provider {
    val apiJarPattern = Regex("""boss-plugin-api-(\d+)\.(\d+)\.(\d+)\.jar""")
    file("$bossPluginApiPath/build/libs").listFiles()
        ?.mapNotNull { jar -> apiJarPattern.matchEntire(jar.name)?.let { m -> jar to m } }
        // Lexicographic (major, minor, patch) — no packing arithmetic that would mis-order
        // components >= 1000.
        ?.maxWithOrNull(
            compareBy(
                { it.second.groupValues[1].toInt() },
                { it.second.groupValues[2].toInt() },
                { it.second.groupValues[3].toInt() }
            )
        )?.first
        ?: error(
            "No boss-plugin-api jar found in $bossPluginApiPath/build/libs — " +
                "run ./gradlew buildPluginJar in the sibling boss-plugin-api checkout first."
        )
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: newest boss-plugin-api JAR from sibling repo
        compileOnly(files(newestApiJar))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // No HTTP client. AI requests go through the AI Gateway plugin, so the four Ktor
    // artifacts that used to be bundled here are gone - which also removes a
    // loader-constraint hazard, since the host deliberately excludes the ktor stack.

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-llmrpa-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS LLM RPA Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.llmrpa.LlmrpaDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Fat JAR for out-of-process plugin execution
tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    from("src/main/resources")
}
