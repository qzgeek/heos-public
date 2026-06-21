import java.util.Properties

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.7.10"
}

rootProject.name = "luoos"

val foliaVersions = listOf("1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.11", "26.1", "26.1.1", "26.1.2")
foliaVersions.forEach { version ->
    include("folia:$version")
    project(":folia:$version").projectDir = file("folia/versions/$version")
}
