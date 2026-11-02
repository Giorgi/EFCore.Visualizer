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

// Rider's test framework API differs between versions: shared test logic lives in src/test/kotlin, the
// version-specific bits (test annotations, a few helpers) in src/test/kotlin-<251|262>.
val riderMajor = providers.gradleProperty("riderVersion").get().substringBefore('.').toInt()
sourceSets["test"].kotlin.srcDir(if (riderMajor >= 2026) "src/test/kotlin-262" else "src/test/kotlin-251")

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
        jetbrainsRuntime()
        // Embedded browser (JCEF) APIs, used by the plan-tab rendering test: separate modules in 2026.2, part of the
        // core platform jars in 2025.1 (2026.1 not checked).
        if (riderMajor >= 2026) {
            bundledModule("intellij.platform.ui.jcef")
            bundledModule("intellij.libraries.jcef")
        }
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
        ideaVersion {
            // Oldest Rider the tests and the Plugin Verifier pass on (2025.1). No upper bound.
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // Comma-separated Rider versions (gradle.properties: verifyRiderVersions); falls back to the build target.
            providers.gradleProperty("verifyRiderVersions").orElse(providers.gradleProperty("riderVersion")).get()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { version ->
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider, version) { useInstaller = false }
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

// Rider 2025.x SDK declares a test-only service for the bundled JavaScript "Live Edit" plugin whose class it does not
// ship; it fails when a debug session starts. The tests don't need Live Edit, so leave it out of the test sandbox.
tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
    if (riderMajor < 2026) disabledPlugins.add("com.intellij.plugins.html.instantEditing")
}

tasks.withType<Test>().configureEach {
    useTestNG()
    // Test methods live in abstract *TestBase classes; select the concrete per-version classes by name, since
    // class scanning does not pick up inherited TestNG methods.
    isScanForTestClasses = false
    include("**/*Test.class")
    // Let JCEF (embedded browser) start inside tests, off-screen, so the plan tab can be rendered and checked.
    systemProperty("ide.browser.jcef.testMode.enabled", "true")
    systemProperty("ide.browser.jcef.headless.enabled", "true")
    systemProperty("ide.browser.jcef.sandbox.enable", "false")
    systemProperty("intellij.build.test.ignoreFirstAndLastTests", "true")
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}
