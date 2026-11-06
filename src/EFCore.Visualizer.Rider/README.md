# EFCore Visualizer for Rider

View the execution plan of an Entity Framework Core query while you debug, without leaving Rider.
Supports SQL Server, PostgreSQL, Oracle and SQLite.

This is the Rider version of the [EFCore Visualizer](https://github.com/Giorgi/EFCore.Visualizer) for Visual
Studio. It shows the same plan pages.

<!-- TODO: screenshot of a plan tab in Rider -->

## Requirements

* Rider 2025.1 or newer.
* An app that uses EF Core 7 or newer, on .NET 6 or newer.
* **Allow property evaluations and other implicit function calls** turned on, in **Settings | Build, Execution,
  Deployment | Debugger**. It's on by default. The plugin calls methods in your app to get the plan; with the
  setting off, it says so instead of trying.

## Installation

In Rider, open **Settings | Plugins | Marketplace**, search for **EFCore Visualizer** and install it.

To install a build that isn't on the Marketplace, for example the `efcore-visualizer-rider` artifact of a CI run:
**Settings | Plugins**, the gear icon, **Install Plugin from Disk…**, and choose the zip.

## Usage

1. Debug your app and pause it, for example at a breakpoint, where a query is in scope.
2. In **Variables** or **Watches**, right-click the query (an `IQueryable` from EF Core, or a `DbSet`) and choose
   **Visualize Query Plan**.

The plan opens in an editor tab. The menu item appears only on EF Core queries, whatever the variable's declared
type (`IQueryable`, `IOrderedQueryable`, `IEnumerable`, ...), and only in .NET debug sessions.

While the plan is fetched, the status bar shows the elapsed time.

### Tabs

* Each variable gets its own tab, so you can compare plans side by side.
* Visualizing the same variable again brings its tab to the front. If the query changed in the meantime (another
  `Where` or `OrderBy`, a new query assigned, a different parameter value), the tab gets the new plan.
* A second click on a variable whose plan is still being fetched is ignored, so the query doesn't run twice.

## Important: the query runs

For SQL Server, PostgreSQL and Oracle, the plugin shows the *actual* plan, so it **runs the query** in your app, on
your app's connection and transaction:

* Getting the plan takes as long as the query, and your app stays paused until it finishes. It can't be cancelled:
  the debugger can't interrupt an evaluation.
* Rider stops an evaluation after 1 second by default (**Evaluation timeout**, in **Settings | Build, Execution,
  Deployment | Debugger**). While it gets a plan, the plugin raises that limit to 30 seconds for the debug session,
  or keeps yours if it's higher, and puts it back afterwards. Your saved setting doesn't change. Meanwhile your own
  Watches in that session get the higher limit too.
* The query is also limited by your app's database command timeout: 30 seconds by default for SQL Server and
  PostgreSQL.

For SQLite, the plugin only asks for the estimated plan (`EXPLAIN QUERY PLAN`), so the query doesn't run.

## When there's no plan

If the plugin can't get the plan, a short warning says why, and the tab shows the query's SQL instead. Visualizing
the variable again tries the plan again. Errors stay on screen until you close them.

## Known issues

* Your app must run on the same machine as Rider. The plugin loads a helper DLL from Rider's folders into your app,
  and your app writes the plan page to its temp folder, where Rider opens it. Debugging an app in Docker, WSL or on
  another machine isn't supported yet: the plugin says so when it can't load the helper or find the page.
* .NET Framework apps aren't supported: the helper targets .NET 6.
* If your app uses Application Insights, getting the plan can fail with "Cannot evaluate expression since the
  function evaluation requires all threads to run". The same happens in Visual Studio
  ([#28](https://github.com/Giorgi/EFCore.Visualizer/issues/28)). Disable Application Insights while debugging.
* There are no **Refresh** or **Copy SQL** buttons yet, as in the VS Code extension, and the page doesn't follow
  Rider's color theme.

## How it works

The plugin has no Rider backend part. It uses the debugger's expression evaluation, the same as **Watches**:

1. `System.Reflection.Assembly.LoadFrom(@"<copy>/netcoreapp/EFCore.Visualizer.Core.dll")` loads the helper
   (`src/EFCore.Visualizer.Core`, shared with the VS and VS Code extensions) into the paused app.
2. `IQueryableObjectSource.QueryPlanHelper.GetPlanFile(<expression>)` runs the plan query and writes an HTML page
   to the app's temp folder. If that fails, `GetQueryFile` writes the SQL page instead.
3. The plugin opens the page in an editor tab with Rider's embedded browser (JCEF), and deletes it when the tab
   closes.

`GetQueryString` (the SQL with its parameter values, which doesn't run the query) tells whether a tab is still up
to date. The helper is loaded from a copy in Rider's system folder, one per plugin version, because the app keeps
the DLL it loaded locked on Windows, and Rider updates plugins in place.

## Development

### Layout

* `src/main/kotlin/efcore/visualizer/`
  * `VisualizeQueryPlanAction.kt`: the context menu item. Decides when it's offered, and runs a visualization.
  * `QueryPlanVisualizer.kt`: loads the helper, gets the plan or the SQL page, and the SQL to compare tabs with.
  * `DebuggeeEvaluator.kt`: evaluates expressions through the debugger and reads the results, including exception
    messages and Rider's timeouts.
  * `EvaluationTimeout.kt`: raises the debug session's evaluation limit while the plugin evaluates.
  * `HelperCopy.kt`: the per-version copy of the helper.
  * `QueryPlanPresenter.kt`: the tabs (one per variable) and the notifications.
  * `PlanProgress.kt`: the progress indicator.
* `src/main/resources/META-INF/`: `plugin.xml` (ID, description, change notes, the action) and `pluginIcon.svg`.
* `src/test/kotlin/`: the tests, which debug `samples/SampleApp` (the sample the VS and VS Code extensions use too)
  in a real Rider. Rider's test framework differs between versions, so the test annotations and a few helpers are in
  `src/test/kotlin-251` (2025.1-2025.2), `kotlin-253` (2025.3), `kotlin-25x` (both) and `kotlin-261` (2026.1+).

### Prerequisites

* JDK 21. If there's none, Gradle downloads one (the foojay toolchain resolver).
* A .NET SDK on `PATH`, to build `EFCore.Visualizer.Core` and the sample app. **To run the tests on Rider 2025.x,
  use the .NET 8 or 9 SDK**: Rider 2025.x can't load a solution with the MSBuild of .NET 10 or newer ("Unknown
  tools version: 18.x"). Rider 2026.x works with any of them.
* Disk space: Gradle downloads the Rider SDK, about 4 GB, and unpacks it to about 10 GB, per Rider version. Set
  `GRADLE_USER_HOME` to put it on another drive.

All commands run in this folder (`src/EFCore.Visualizer.Rider`).

### Build

```shell
./gradlew buildPlugin
```

The plugin zip is in `build/distributions`. `./gradlew runIde` starts a Rider with the plugin installed, in a
sandbox.

### Rider versions

`riderVersion` in `gradle.properties` is the Rider the plugin is built and tested against. It's 2025.1.9, the
oldest supported version (`sinceBuild = 251`), which is what releases are built with. To use another one, pass
`-PriderVersion=2026.2.4`, or put `riderVersion=2026.2.4` in your own `~/.gradle/gradle.properties`, which takes
precedence over the project's.

### Tests

```shell
./gradlew test -PriderVersion=2026.2.4
./gradlew test -PriderVersion=2026.2.4 --tests efcore.visualizer.VisualizeActionTest   # one class
```

Each test class starts Rider, opens and builds the sample solution and debugs it, so a run takes a few minutes.

* `DebuggeeHelperTest`: the helper, the plan and SQL pages, error messages, the SQL comparison, the evaluation limit,
  and pressing Continue during an evaluation.
* `VisualizeActionTest`: the menu item on the real Variables view: for which values it's offered, a plan, a double
  click, an up-to-date tab, the helper copy, and the setting check.
* `PlanTabRenderTest`: renders the plan page in Rider's embedded browser and checks its CSS and script. It needs an
  X display, so it only runs on Linux under `xvfb-run -a ./gradlew test ...`. Elsewhere it's left out.
* `PlanProgressTest`: the progress text, without Rider.

Rider's log of a test run is in `.intellijPlatform/sandbox/*/<version>/log-test`.

### Plugin Verifier

```shell
./gradlew verifyPlugin
```

checks the plugin against the Rider versions in `verifyRiderVersions` (`gradle.properties`): binary compatibility,
and use of internal, deprecated or experimental API. Two deprecated overrides are expected: Rider 2025.1 still
requires them.

### Behind a TLS-inspecting proxy

If Gradle fails with "PKIX path building failed" because a proxy re-signs HTTPS traffic, let Java use the operating
system's certificates: on Windows, add `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT` to
`~/.gradle/gradle.properties`. The Plugin Verifier runs in its own Java process; set
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` for it.

### CI

[`.github/workflows/rider.yml`](../../.github/workflows/rider.yml) runs on changes to this folder,
`src/EFCore.Visualizer.Core` and the sample app. On Ubuntu, for Rider 2025.1.9 and 2026.2.4, it runs the tests
(under `xvfb-run`) and the Plugin Verifier, shows each test's result on the run page, and uploads the test results.
The 2025.1.9 job uploads the plugin zip.

### Releasing

1. Set `version` in `build.gradle.kts`, and add the version's notes to `<change-notes>` in `plugin.xml`.
2. Take the plugin zip from the CI run of that commit (the 2025.1.9 job's `efcore-visualizer-rider` artifact).
3. Upload it on the [JetBrains Marketplace](https://plugins.jetbrains.com/). The first upload is done on the
   website; the plugin ID (`dev.giorgi.efcore.visualizer`) can't change after it. JetBrains reviews new plugins and
   updates before they appear.

## License

[GPL-3.0](../../LICENSE), like the rest of the repository.
