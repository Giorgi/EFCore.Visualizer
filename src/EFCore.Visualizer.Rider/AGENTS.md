# AGENTS.md

Guidance for AI coding agents working on the Rider plugin. The repository-wide `AGENTS.md` at the root covers the
shared code and the Visual Studio extension; read it too.

## What this is

An IntelliJ Platform plugin (Kotlin) for Rider that shows the execution plan of an EF Core query while debugging a
.NET app. `README.md` is the page for users and occasional contributors, so keep design details and work items out
of it. `src/main/resources/META-INF/plugin.xml` has the Marketplace description and change notes.

It reuses the debuggee-side code (`../EFCore.Visualizer.Core`) and its page templates, like the VS Code extension.
It has no Rider backend (ReSharper) part: everything goes through the IntelliJ debugger API.

## Layout

- `src/main/kotlin/efcore/visualizer/`
  - `VisualizeQueryPlanAction.kt`: the "Visualize Query Plan" item in `XDebugger.ValueGroup`. `update()` offers it
    only in a .NET session (`DotNetDebugProcess`, found by class name) and for values Rider shows as an EF Core
    query: the VS Code regex `EntityQueryable|IncludableQueryable|InternalDbSet`, matched against the node's type
    or, when Rider shows none (it shows an EF query only as its value, `EntityQueryable<Blog>`), its raw value. It
    ignores a second click on a variable that is still running, checks the "Allow property evaluations and other
    implicit function calls" setting, and asks the presenter whether the variable's tab is up to date.
  - `QueryPlanVisualizer.kt`: loads the helper, then `GetPlanFile`, falling back to `GetQueryFile` (both errors
    reported if both fail; no fallback once the debugger isn't paused). `queryString()` is the SQL tabs are compared
    with (`GetQueryString`).
  - `DebuggeeEvaluator.kt`: evaluates through `XDebuggerEvaluator` (as Watches does) and renders the `XValue` to
    text. Evaluations start on the UI thread. A func-eval exception arrives as a value ("Exception of type 'X' was
    thrown") with one child, `Exception`; its `Message` is read from there. "Timed out after N ms" is Rider's
    evaluation limit. An evaluation fails, instead of hanging, when the session resumes or stops.
  - `EvaluationTimeout.kt`: Rider's evaluation limit is 1 s by default. While the plugin evaluates, it raises the
    session's `SessionProperties.evaluationTimeout` (reached by reflection: `DotNetDebugProcess.getSessionProxy()`
    isn't on the compile classpath) to 30 s unless it's higher, and restores it; a watchdog ends visualizations the
    debugger never answers. A change takes effect immediately, measured on 2026.2.4.
  - `HelperCopy.kt`: the helper is loaded from a copy in Rider's system folder, per plugin version (content hash).
    The debuggee locks the DLL it loaded on Windows, and Rider updates plugins in place. `LoadFrom` of a second path
    of the same assembly works in .NET Core.
  - `QueryPlanPresenter.kt`: one tab per (session, expression), via `HTMLEditorProvider` (a JCEF editor tab), and
    the notifications (errors in a sticky group; the text is HTML, so it's escaped). An application service, so
    tests replace it.
  - `PlanProgress.kt`: the status bar progress, same text as VS Code's.
  - `PageTheme.kt`: Rider's embedded browser always reports a light color scheme, and the templates go dark only
    through `prefers-color-scheme`. Before a page is shown, its `prefers-color-scheme` conditions are turned into
    "all"/"not all" for Rider's theme and the background becomes the editor's, as VS Code's `webview.ts` does.
  - `PlanDatabase.kt`, `PlanTabIconProvider.kt`: a plan tab shows its database's icon, Rider's own
    (`AllIcons.Providers`; not Simple Icons, which had to remove Microsoft's logos). The database is recognised from
    the page: each template has its own scripts or title (`PlanDatabaseTest` checks the real ones). The SQL-only page
    keeps the HTML editor's globe.
- `src/test/kotlin/`: test logic (`*TestBase`), `SampleAppDebuggerTestBase` (breakpoint, settings), `RiderCompat`
  (test framework helpers). The concrete test classes (`Tests.kt`) only carry the annotations and call the base
  methods: Rider reads test annotations from the declaring class.
- Supported: Rider 2026.2 and newer (`sinceBuild` 262), one code base. Rider's internal APIs move between versions
  (the debugger link provider changed package in 2026.2); if a new Rider breaks one, the Plugin Verifier on the newest
  Rider shows it.
- `src/test/testData/solutions/SampleApp/SampleApp.sln`: the test solution. `prepareTestData` copies
  `../../samples/SampleApp` next to it, without `launchSettings.json` (with it, Rider picks a launch profile
  itself), so the app runs with SQLite. The tests rely on the sample's variable names and its `// BREAKPOINT` line.

## Build and test

See `README.md` for the commands. Things that aren't obvious:

- Each test class starts Rider, opens and builds the solution, and debugs it: about 1 minute each.
  `--tests efcore.visualizer.VisualizeActionTest` runs one class. Rider's logs are in
  `.intellijPlatform/sandbox/*/<version>/log-test`.
- To try the plugin by hand, use `runRider` (also the shared run configuration in `.run`), not `runIde`. `runIde`
  runs the Rider SDK build the tests use, which has no embedded browser engine (no `plugins/jcef-plugin/jcef`), so
  the plan tab stays empty. `runRider` runs the Rider install named by the `localRider` Gradle property (set it in
  `~/.gradle/gradle.properties`), or else downloads Rider's installer build (`runRiderVersion` in
  `gradle.properties`). Both open `samples/SampleApp`.
- The test framework turns method calls in watches off; `SampleAppDebuggerTestBase.stopAtBreakpoint()` turns them
  on, and `RiderCompat.disableMethodCalls()` restores the previous value (it doesn't switch them off).
- The test framework raises the evaluation limit, so set it explicitly (`EvaluationTimeout.setLimit`) when a test
  depends on it.
- On Windows the test sandbox disables the C++ plugin (its console filter logs an error that fails the tests).
- After renaming a test source folder, Kotlin's incremental compilation can drop classes ("did not discover any
  tests"): delete `build/kotlin` and `build/classes/kotlin`.
- The Plugin Verifier reports a deprecated override (`XCompositeNode.tooManyChildren(int)`): it's still abstract, so
  it can't go.
- Compile against a Kotlin API no newer than the runtime bundled with the oldest supported Rider (`apiVersion`; 2.4
  in 2026.2): the plugin uses Rider's Kotlin runtime.

## Releasing

1. Set `version` in `build.gradle.kts`, and add its notes to `<change-notes>` in `plugin.xml`.
2. Take the plugin zip from that commit's CI run: the 2026.2.4 job's `efcore-visualizer-rider` artifact (built
   against the oldest supported Rider).
3. Upload it on the JetBrains Marketplace. The first upload is done on the website, and the plugin ID
   (`dev.giorgi.efcore.visualizer`) can't change after it. JetBrains reviews uploads before they appear.
