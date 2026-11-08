import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.10"
}

group = "dev.etran"
version = providers.gradleProperty("pluginVersion").getOrElse("1.1.0")

repositories {
    mavenCentral()
    // Spigot API 1.8.8 - we compile against the OLDEST supported API so nothing newer can slip in.
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // Spigot API 1.16.5 for the tiny "modern" source set (ChunkData based void generator).
    maven("https://repo.papermc.io/repository/maven-public/")
}

// ---------------------------------------------------------------------------------------------
// Source sets
// ---------------------------------------------------------------------------------------------
// `main`   -> everything, compiled against Spigot 1.8.8 (binary compatible with 1.8 -> 26.x).
// `modern` -> one class overriding ChunkGenerator#generateChunkData (1.13+ only), loaded by name at runtime.
val modern: SourceSet by sourceSets.creating

dependencies {
    // --- Bukkit API (oldest supported) ---
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("com.google.guava:guava:21.0")
    compileOnly("org.yaml:snakeyaml:1.30")
    compileOnly("org.jetbrains:annotations:24.0.1")

    // --- BedWars1058 API ---
    // Always the jar shipped in ./libs (bedwars-api-24.9.jar); nothing is fetched from a remote repository.
    // -PbedwarsApiJar=/path/to/other.jar compiles against a different API jar instead.
    val explicitJar = providers.gradleProperty("bedwarsApiJar").orNull
    val localApi = fileTree("libs") { include("*.jar") }
    if (explicitJar == null && localApi.isEmpty) {
        throw GradleException("No BedWars1058 API jar found: put bedwars-api-24.9.jar into ./libs or pass -PbedwarsApiJar=<jar>.")
    }
    compileOnly(if (explicitJar != null) files(explicitJar) else localApi)

    // --- Modern void generator ---
    "modernCompileOnly"("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT") { isTransitive = false }
    "modernCompileOnly"("org.jetbrains:annotations:24.0.1")

    // --- Tests (no server needed; Bukkit's YAML classes are used for the file round trips) ---
    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    testImplementation("com.google.guava:guava:21.0")
    testImplementation("org.yaml:snakeyaml:1.30")
    testImplementation("commons-lang:commons-lang:2.6")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------------------------
// Compiler settings: Java 11 bytecode, like BedWars1058 and BedWars1058-MapLoader.
// ---------------------------------------------------------------------------------------------
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Fail the build if a JDK API newer than Java 11 is used by accident.
        freeCompilerArgs.addAll("-Xjdk-release=11", "-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// Packaging
// ---------------------------------------------------------------------------------------------
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("BedWars1058-MultiGroup-${project.version}.jar")
    from(modern.output)

    // Bukkit does not ship Kotlin (and Spigot's library loader does not exist before 1.16.5): shade and relocate
    // it so it can never clash with BedWars1058-MapLoader's or any other plugin's Kotlin.
    relocate("kotlin", "dev.bwmultigroup.libs.kotlin")
    relocate("org.jetbrains.annotations", "dev.bwmultigroup.libs.jb.annotations")
    relocate("org.intellij.lang.annotations", "dev.bwmultigroup.libs.intellij.annotations")

    // NOTE: no minimize(): the void generators are loaded by name.
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**", "META-INF/versions/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
