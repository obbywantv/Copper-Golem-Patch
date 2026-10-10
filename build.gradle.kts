plugins {
    `java-library`
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.cgp"
version = "0.3.0"

// --- Supported Minecraft versions ----------------------------------------
// 1.21.9 is the floor: it is the version that introduced Copper Golems, so
// nothing below it has a golem to patch. All four APIs this plugin depends on
// (CopperGolem, ItemTransportingEntityValidateTargetEvent, Brigadier Commands,
// LifecycleEvents) are already present in 1.21.9.
val supportedVersions = linkedMapOf(
    "1.21.9" to "1.21.9-R0.1-SNAPSHOT",
    "1.21.10" to "1.21.10-R0.1-SNAPSHOT",
    "1.21.11" to "1.21.11-R0.1-SNAPSHOT",
    "26.1.2" to "26.1.2.build.+",
    "26.2" to "26.2.build.+",
    "26.3" to "26.3.build.+",
)

/**
 * Which version's API to compile against. Defaults to the floor: the published
 * artifact is built against 1.21.9 and works forward across the whole matrix.
 * The other values are for verification builds — "does the source still compile
 * against the recent API?".
 *
 *   ./gradlew build -PmcVersion=26.2
 */
val mcVersion = providers.gradleProperty("mcVersion").getOrElse(supportedVersions.keys.first())
val paperApiVersion = supportedVersions[mcVersion]
    ?: error("Unsupported Minecraft version: $mcVersion (expected one of ${supportedVersions.keys.joinToString(", ")})")

/**
 * Which version runServer launches. Deliberately independent of the one above,
 * so we can check that the jar built against the floor really does load on a
 * recent server:
 *
 *   ./gradlew runServer -PmcVersion=1.21.9 -PserverVersion=26.2
 */
val serverVersion = providers.gradleProperty("serverVersion").getOrElse(mcVersion)

/** Java a given server version requires: 21 up to 1.21.11, 25 from 26.x on. */
fun javaFor(version: String) = if (version.startsWith("1.")) 21 else 25

// paper-api 26.x publishes Gradle metadata saying "requires Java 25", so it
// cannot be consumed while targeting Java 21 bytecode. We therefore emit the
// bytecode the compiled-against version demands. The default build (floor
// 1.21.9) produces Java 21 bytecode, which loads on both Java 21 and Java 25
// servers — that is the artifact to publish.
val bytecodeVersion = javaFor(mcVersion)

java {
    // Pinned to 25 regardless of the target: reading the paper-api 26.x jars
    // (class file 69) at compile time requires it.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeVersion)
}

tasks.processResources {
    // api-version declares the supported floor, not the compiled-against
    // version: Paper accepts a plugin whose api-version predates the server.
    //
    // Without the two input declarations below, Gradle considers this task
    // up to date when only these values change and reuses the previously
    // generated paper-plugin.yml — a version bump produced a jar named 0.2.0
    // whose descriptor still claimed 0.1.0.
    inputs.property("pluginVersion", project.version)
    inputs.property("apiVersion", supportedVersions.keys.first())
    filesMatching("paper-plugin.yml") {
        expand(
            "version" to project.version,
            "apiVersion" to supportedVersions.keys.first(),
        )
    }
}

tasks.runServer {
    minecraftVersion(serverVersion)
    // A world opened by a recent version can never be reopened by an older
    // one, so each test version gets its own directory.
    runDirectory.set(layout.projectDirectory.dir("run/$serverVersion"))
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(javaFor(serverVersion)))
        }
    )
}
