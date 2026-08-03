plugins {
    `java-library`
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.cgp"
version = "0.2.0"

// --- Matrice des versions Minecraft supportees ---------------------------
// Le plancher est 1.21.9 : c'est la version qui a introduit les Copper
// Golems, donc rien en dessous n'a de golem a patcher. Les quatre APIs dont
// le plugin depend (CopperGolem, ItemTransportingEntityValidateTargetEvent,
// Brigadier Commands, LifecycleEvents) sont toutes presentes des 1.21.9.
val supportedVersions = linkedMapOf(
    "1.21.9" to "1.21.9-R0.1-SNAPSHOT",
    "1.21.10" to "1.21.10-R0.1-SNAPSHOT",
    "1.21.11" to "1.21.11-R0.1-SNAPSHOT",
    "26.1.2" to "26.1.2.build.+",
    "26.2" to "26.2.build.+",
)

/**
 * Version dont on consomme l'API a la compilation. Par defaut le plancher :
 * l'artefact publie est compile contre 1.21.9 et fonctionne en avant sur
 * toute la matrice. Les autres valeurs servent aux builds de verification
 * ("le source compile-t-il toujours contre l'API recente ?").
 *
 *   ./gradlew build -PmcVersion=26.2
 */
val mcVersion = providers.gradleProperty("mcVersion").getOrElse(supportedVersions.keys.first())
val paperApiVersion = supportedVersions[mcVersion]
    ?: error("Version Minecraft non supportee : $mcVersion (attendu : ${supportedVersions.keys.joinToString(", ")})")

/**
 * Version du serveur lance par runServer. Independante de la precedente,
 * pour pouvoir verifier que le jar compile contre le plancher charge bien
 * sur un serveur recent :
 *
 *   ./gradlew runServer -PmcVersion=1.21.9 -PserverVersion=26.2
 */
val serverVersion = providers.gradleProperty("serverVersion").getOrElse(mcVersion)

/** Java requis par une version de serveur donnee : 21 jusqu'a 1.21.11, 25 des 26.x. */
fun javaFor(version: String) = if (version.startsWith("1.")) 21 else 25

// paper-api 26.x publie une metadonnee Gradle "requiert Java 25" : impossible
// de le consommer en ciblant du bytecode 21. On emet donc le bytecode exige
// par la version compilee. Le build par defaut (plancher 1.21.9) produit du
// bytecode 21, qui charge aussi bien sur un serveur Java 21 que Java 25 -
// c'est lui l'artefact a publier.
val bytecodeVersion = javaFor(mcVersion)

java {
    // Toolchain fixee a 25 : necessaire pour *lire* les jars paper-api 26.x
    // (class file 69) a la compilation, quelle que soit la cible emise.
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
    // api-version declare le plancher supporte, pas la version compilee :
    // Paper accepte un plugin dont l'api-version est anterieure au serveur.
    // Sans ces deux declarations, Gradle considere la tache a jour quand
    // seules ces valeurs changent et reutilise le paper-plugin.yml genere
    // precedemment : un bump de version produisait un jar nomme 0.2.0 dont
    // le descripteur annoncait encore 0.1.0.
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
    // Un monde ouvert par une version recente ne peut plus etre rouvert par
    // une ancienne : chaque version de test a donc son propre repertoire.
    runDirectory.set(layout.projectDirectory.dir("run/$serverVersion"))
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(javaFor(serverVersion)))
        }
    )
}
