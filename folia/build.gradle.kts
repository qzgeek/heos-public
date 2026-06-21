plugins {
    id("base")
    id("com.gradleup.shadow") version "8.3.5" apply false
}

group = "luoos"

val modMetadata = loadHeosmodMetadata(file("src/main/java/heos/Heosmod.java"))
val modId = modMetadata.getValue("MOD_ID")
val modName = modMetadata.getValue("MOD_NAME")
val modVersion = modMetadata.getValue("MOD_VERSION")
val modDescription = modMetadata.getValue("MOD_DESCRIPTION")
val modAuthor = modMetadata.getValue("MOD_AUTHOR")
val modHomepage = modMetadata.getValue("MOD_HOMEPAGE")

version = modVersion

val foliaSourceRoot = project.layout.projectDirectory.dir("src/main")
val releaseJars = layout.buildDirectory.dir("release-jars")
val supportedFoliaVersions = mapOf(
    "1.20.1" to "1.20-1.20.1",
    "1.20.2" to "1.20.2-1.20.3",
    "1.20.4" to "1.20.4",
    "1.20.6" to "1.20.5-1.20.6",
    "1.21.4" to "1.21-1.21.4",
    "1.21.5" to "1.21.5",
    "1.21.6" to "1.21.6-1.21.7",
    "1.21.8" to "1.21.8-1.21.10",
    "1.21.11" to "1.21.11",
    "26.1" to "26.1",
    "26.1.1" to "26.1.1",
    "26.1.2" to "26.1.2"
)
val foliaApiVersions = mapOf(
    "26.1" to "26.1.2.build.8-stable",
    "26.1.1" to "26.1.2.build.8-stable",
    "26.1.2" to "26.1.2.build.8-stable"
)

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.gradleup.shadow")

    group = rootProject.group
    version = project(":folia").version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        val foliaApiVersion = foliaApiVersions[project.name] ?: "${project.name}-R0.1-SNAPSHOT"
        "compileOnly"("dev.folia:folia-api:$foliaApiVersion")
        nettyCompileOnly("netty-buffer", "4.1.118.Final")
        nettyCompileOnly("netty-common", "4.1.118.Final")
        nettyCompileOnly("netty-transport", "4.1.118.Final")
        "compileOnly"("org.apache.logging.log4j:log4j-core:2.24.3")
        "implementation"("org.xerial:sqlite-jdbc:3.49.1.0")
        "implementation"("at.favre.lib:bcrypt:0.10.2")
    }

    val isMinecraft26Plus = project.name.substringBefore('.').toIntOrNull()?.let { it >= 26 } == true
    val javaVersion = if (isMinecraft26Plus) 25 else 21
    val foliaArchiveBaseName = "${modId}-folia-mc${supportedFoliaVersions[project.name] ?: project.name}"

    extensions.configure<JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        withSourcesJar()
    }

    extensions.configure<SourceSetContainer>("sourceSets") {
        named("main") {
            java.srcDir(foliaSourceRoot.dir("java"))
            resources.srcDir(foliaSourceRoot.dir("resources"))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
    }

    tasks.withType<ProcessResources>().configureEach {
        from(project.layout.projectDirectory.dir("src/main/resources/data")) {
            into("data")
        }
        filesMatching("plugin.yml") {
            expand(
                mapOf(
                    "mod_id" to modId,
                    "version" to project.version,
                    "mod_name" to modName,
                    "mod_description" to modDescription,
                    "mod_author" to modAuthor,
                    "mod_homepage" to modHomepage,
                    "api_version" to if (isMinecraft26Plus) project.name else "1.21"
                )
            )
        }
    }

    tasks.named<Jar>("sourcesJar") {
        archiveBaseName.set(foliaArchiveBaseName)
        archiveVersion.set(project.version.toString())
    }

    tasks.named<Jar>("jar") {
        enabled = false
    }

    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set(foliaArchiveBaseName)
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
    }

    tasks.named("build") {
        dependsOn(tasks.named("shadowJar"))
    }
}

val buildAll by tasks.registering {
    group = "build"
    description = "Builds all Folia version jars."
    dependsOn(subprojects.map { it.tasks.named("assemble") })
}

val collectFoliaJars by tasks.registering(Copy::class) {
    group = "build"
    description = "Collects all Folia version jars into root build/release-jars."
    dependsOn(subprojects.map { it.tasks.named("shadowJar") })
    into(releaseJars)
    subprojects.forEach { versionProject ->
        from(versionProject.tasks.named("shadowJar").map { it.outputs.files }) {
            include("*.jar")
        }
    }
}

tasks.named("build") {
    dependsOn(buildAll)
    finalizedBy(collectFoliaJars)
}

tasks.named("assemble") {
    dependsOn(buildAll)
    finalizedBy(collectFoliaJars)
}

fun loadHeosmodMetadata(file: File): Map<String, String> {
    val pattern = Regex("""public\s+static\s+final\s+String\s+(\w+)\s*=\s*"((?:\\.|[^"])*)";""")
    return pattern.findAll(file.readText()).associate { match ->
        val key = match.groupValues[1]
        val value = match.groupValues[2]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
        key to value
    }
}

fun DependencyHandler.nettyCompileOnly(module: String, version: String) {
    val cachedJar = file(System.getenv("GRADLE_USER_HOME") ?: "D:/.gradle")
        .resolve("caches/modules-2/files-2.1/io.netty/$module/$version")
        .takeIf { it.isDirectory }
        ?.walkTopDown()
        ?.firstOrNull { it.isFile && it.name == "$module-$version.jar" }
    if (cachedJar != null) {
        add("compileOnly", files(cachedJar))
    } else {
        add("compileOnly", "io.netty:$module:$version") {
            (this as ExternalModuleDependency).isTransitive = false
        }
    }
}
