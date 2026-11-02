import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "efcore.visualizer"
version = "0.0.1"

repositories {
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/maven-central")
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
        jetbrainsRuntime()
        // Rider's test framework (solution/debugger scripting API) ships inside the SDK.
        bundledLibrary(provider { project.intellijPlatform.platformPath.resolve("lib/testFramework.jar").toString() })
    }
    testImplementation("org.testng:testng:7.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        name = "EFCore Visualizer"
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider, providers.gradleProperty("riderVersion").get()) {
                useInstaller = false
            }
        }
    }
    buildSearchableOptions = false
}

// Debugger-agnostic helper laid out like the VSIX: <root>/netcoreapp/<dll> + <root>/Resources/...
val helperRoot = layout.buildDirectory.dir("helper")
val buildHelper by tasks.registering(Exec::class) {
    val project = rootDir.resolve("../src/EFCore.Visualizer.DebuggeeHelper/EFCore.Visualizer.DebuggeeHelper.csproj")
    commandLine("dotnet", "build", project.path, "-c", "Release", "-o", helperRoot.get().dir("build").asFile.path)
    // sync (not copy) so pages generated into these folders by earlier runs never end up in the plugin zip.
    doLast {
        sync {
            from(helperRoot.get().dir("build")) { include("EFCore.Visualizer.DebuggeeHelper.dll", "EFCore.Visualizer.DebuggeeHelper.pdb") }
            into(helperRoot.get().dir("netcoreapp"))
        }
        sync {
            from(rootDir.resolve("../src/EFCore.Visualizer/Resources"))
            into(helperRoot.get().dir("Resources"))
        }
    }
}

// Ship the helper inside the plugin, mirroring the VSIX: <plugin>/netcoreapp/*.dll and <plugin>/Resources/**
tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(buildHelper)
    from(helperRoot) {
        include("netcoreapp/**", "Resources/**")
        into(intellijPlatform.projectName.map { it })
    }
}

tasks.withType<Test>().configureEach {
    useTestNG()
    systemProperty("intellij.build.test.ignoreFirstAndLastTests", "true")
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}
