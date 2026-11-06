import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
    // The plugin uses the Kotlin runtime bundled with Rider (kotlin.stdlib.default.dependency=false): 2.1 in 2025.1,
    // the oldest supported. Compiling against that API keeps newer stdlib calls, which would fail there, out.
    compilerOptions { apiVersion.set(KotlinVersion.KOTLIN_2_1) }
}

// Rider's test framework API differs between versions: shared test logic lives in src/test/kotlin, the
// version-specific bits (test annotations, a few helpers) in src/test/kotlin-<251|253|262>:
//   251: 2025.1-2025.2 (@TestEnvironment, File test-data marker)   253: 2025.3 (@TestSettings, File marker)
//   262: 2026.x (@TestSettings, Path marker, getSessionTabIfInitialized)
// riderVersion is a release (2025.1.9), a branch EAP (263-EAP-SNAPSHOT, 2026.3-EAP2-SNAPSHOT) or LATEST-EAP-SNAPSHOT.
val (riderMajor, riderMinor) = providers.gradleProperty("riderVersion").get().let { version ->
    val match = Regex("""^(\d+)(?:\.(\d+))?""").find(version)
    val first = match?.groupValues?.get(1)?.toInt()
    when {
        first == null -> 9999 to 9                                 // LATEST-EAP-SNAPSHOT: the newest
        first < 1000 -> (2000 + first / 10) to (first % 10)         // branch number: 263 = 2026.3
        else -> first to (match!!.groupValues[2].toIntOrNull() ?: 0)
    }
}
val testCompatDir = when {
    riderMajor >= 2026 -> "262"
    riderMinor >= 3 -> "253"
    else -> "251"
}
sourceSets["test"].kotlin.srcDir("src/test/kotlin-$testCompatDir")

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
        jetbrainsRuntime()
        // Embedded browser (JCEF) APIs, used by the plan-tab rendering test: separate modules from 2026.2; part of the
        // core platform jars up to 2026.1.
        if (riderMajor > 2026 || (riderMajor == 2026 && riderMinor >= 2)) {
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
val coreDir = rootDir.resolve("../EFCore.Visualizer.Core")
val helperBuild = layout.buildDirectory.dir("helper-build")
val buildHelper by tasks.registering(Exec::class) {
    description = "Builds EFCore.Visualizer.Core, the helper the plugin loads into the debugged app."
    inputs.files(fileTree(coreDir) { exclude("bin/**", "obj/**", "Resources/**") })
    outputs.dir(helperBuild)
    val csproj = coreDir.resolve("EFCore.Visualizer.Core.csproj").path
    val output = helperBuild.get().asFile.path
    commandLine("dotnet", "build", csproj, "-c", "Release", "-o", output)
}
// A Sync (not a copy), so files deleted from Core don't stay in the plugin zip.
val helperRoot = layout.buildDirectory.dir("helper")
val prepareHelper by tasks.registering(Sync::class) {
    from(buildHelper) {
        include("EFCore.Visualizer.Core.dll", "EFCore.Visualizer.Core.pdb")
        into("netcoreapp")
    }
    from(coreDir.resolve("Resources")) { into("Resources") }
    into(helperRoot)
}

// Ship the helper inside the plugin, mirroring the VSIX: <plugin>/netcoreapp/*.dll and <plugin>/Resources/**
tasks.withType<PrepareSandboxTask>().configureEach {
    from(prepareHelper) {
        into(intellijPlatform.projectName.map { it })
    }
}

// Rider 2025.x SDK declares a test-only service for the bundled JavaScript "Live Edit" plugin whose class it does not
// ship; it fails when a debug session starts. The tests don't need Live Edit, so leave it out of the test sandbox.
// On Windows, the bundled C++ plugin's console stack-trace filter asks for a C++ test toolchain ("Environment is not
// specified ... -Dcpp.test.msvc.home"), on the debugged app's console output. The error it logs fails the test. The
// tests don't need C++ support.
tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
    if (riderMajor < 2026) disabledPlugins.add("com.intellij.plugins.html.instantEditing")
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) disabledPlugins.add("com.jetbrains.rider-cpp")
}

// Test data as Rider's test framework expects it: src/test/testData, plus the repository's sample app (the one VS and
// VS Code use) as the project of solutions/SampleApp, next to that folder's SampleApp.sln.
val testData = layout.buildDirectory.dir("testData")
val prepareTestData by tasks.registering(Sync::class) {
    from("src/test/testData")
    from(rootDir.resolve("../../samples/SampleApp")) {
        // Without its launch profiles, Rider runs the app with no arguments, so it uses SQLite. With them, Rider
        // picks a profile itself (it chose Oracle).
        exclude("bin/**", "obj/**", ".vscode/**", "Properties/launchSettings.json", "*.slnx")
        into("solutions/SampleApp/SampleApp")
    }
    into(testData)
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareTestData)
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
