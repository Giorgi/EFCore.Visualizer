import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "dev.giorgi.efcore.visualizer"
version = "1.0.0"

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
    // The plugin uses the Kotlin runtime bundled with Rider (kotlin.stdlib.default.dependency=false): 2.4 in 2026.2,
    // the oldest supported. Compiling against an API no newer than that keeps stdlib calls that would fail there out.
    compilerOptions { apiVersion.set(KotlinVersion.KOTLIN_2_3) }
}

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("riderVersion")) { useInstaller = false }
        jetbrainsRuntime()
        // Embedded browser (JCEF) APIs, used by the plan-tab rendering test.
        bundledModule("intellij.platform.ui.jcef")
        bundledModule("intellij.libraries.jcef")
        // Rider's debugger link providers (the link on query rows).
        bundledModule("intellij.rider.debugger.shared")
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
            // Oldest supported Rider (2026.2): the row link uses Rider's debugger link API as it is from 2026.2. No
            // upper bound.
            sinceBuild = "262"
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

// runRider: Rider with the plugin, to try it by hand. Unlike runIde, it uses a full Rider: the SDK build
// (useInstaller = false, needed for the tests) has no embedded browser engine, so the plan tab stays empty there.
// With localRider (a Rider install folder, e.g. in ~/.gradle/gradle.properties) it runs that Rider; otherwise it
// downloads Rider's installer build (runRiderVersion). Either way in its own sandbox, apart from your settings.
intellijPlatformTesting {
    runIde {
        register("runRider") {
            val localRider = providers.gradleProperty("localRider").orNull
            if (localRider != null) {
                localPath = file(localRider)
            } else {
                type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider
                version = providers.gradleProperty("runRiderVersion")
                useInstaller = true
            }
        }
    }
}
// Both open the repository's sample app, ready to debug.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
    args(rootDir.resolve("../../samples/SampleApp/SampleApp.slnx").canonicalPath)
}

// On Windows, the bundled C++ plugin's console stack-trace filter asks for a C++ test toolchain ("Environment is not
// specified ... -Dcpp.test.msvc.home"), on the debugged app's console output. The error it logs fails the test. The
// tests don't need C++ support.
tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
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
    // On Linux the plan tab test needs an X display for the embedded browser (on CI: xvfb-run). Without one it
    // would only skip, after Rider has opened and built the test solution for it.
    if (System.getProperty("os.name").startsWith("Linux") && System.getenv("DISPLAY").isNullOrEmpty()) {
        exclude("**/PlanTabRenderTest.class")
    }
    // Let JCEF (embedded browser) start inside tests, off-screen, so the plan tab can be rendered and checked.
    systemProperty("ide.browser.jcef.testMode.enabled", "true")
    systemProperty("ide.browser.jcef.headless.enabled", "true")
    systemProperty("ide.browser.jcef.sandbox.enable", "false")
    testLogging {
        events("passed", "skipped", "failed")
        // Rider logs tens of thousands of lines: on CI they'd make GitHub cut the log. They're in the test reports
        // (and the uploaded IDE logs) there.
        showStandardStreams = System.getenv("CI") == null
        exceptionFormat = TestExceptionFormat.FULL
    }
}
