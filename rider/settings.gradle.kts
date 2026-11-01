rootProject.name = "efcore-visualizer-rider"

pluginManagement {
    repositories {
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
        maven("https://cache-redirector.jetbrains.com/maven-central")
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("org.jetbrains.intellij.platform") version "2.19.0"
    }
}
