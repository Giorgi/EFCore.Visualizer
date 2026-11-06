# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

## What this is

A Visual Studio debugger visualizer (VSIX) that shows the execution plan of an EF Core `IQueryable` while debugging. It supports Visual Studio 17.9+ (manifest range `[17.9, 19.0)`) and EF Core 7+.

## Build

There's no lint step. The only automated tests are the VS Code extension's (`src/EFCore.Visualizer.VSCode`) and the Rider plugin's (`src/EFCore.Visualizer.Rider`); see their `AGENTS.md`. They also cover `EFCore.Visualizer.Core`, the code VS shares, but not the VS extension itself. Build the solution with MSBuild from a Visual Studio developer environment. `dotnet build` does not work, because the VSIX project needs the VS SDK build targets.

```
msbuild src/EFCore.Visualizer.slnx -restore /t:Rebuild
```

CI runs the same build. `.github/workflows/build.yml` builds the VSIX in Release on every push and pull request to `main`, and uploads `EFCore.Visualizer.vsix` as an unzipped artifact. `.github/workflows/codeql-analysis.yml` builds the solution for CodeQL. To debug, run `EFCore.Visualizer` from Visual Studio (it starts the Experimental Instance), then debug any app that uses EF Core. `samples/SampleApp` is one: it has a launch profile for each supported database and seeds its own data (see its README). The VSIX project's "Experimental Instance + SampleApp" launch profile opens it in the Experimental Instance. The VS Code extension's and the Rider plugin's tests debug it too, so keep its variable names and its `// BREAKPOINT` line.

**Build order:** there is no project reference between the two Visual Studio projects, both in `src/EFCore.Visualizer.VS`. `EFCore.Visualizer.csproj` packs `..\ObjectSource\bin\$(Configuration)\net6\IQueryableObjectSource.dll` into the VSIX as a `Content` file. The `.slnx` has a `<BuildDependency>` that builds `IQueryableObjectSource` first, so build the solution, not `EFCore.Visualizer.csproj` on its own. Building the project alone can pack a missing or old copy of that DLL.

## Architecture

The code is split across two processes: the editor (Visual Studio, VS Code or Rider) and the *debuggee*, the app being debugged.

- **`src/EFCore.Visualizer.Core`** (net6) is the code every editor shares. It runs inside the debuggee and holds `QueryPlanHelper`, `DatabaseProvider` with its subclasses, and the page templates in `Resources/`. Keep it free of Visual Studio types. VS doesn't load its DLL: `IQueryableObjectSource` compiles the same files into its own DLL (`<Compile Include="..\..\EFCore.Visualizer.Core\*.cs">`). Other debuggers load `EFCore.Visualizer.Core.dll` with `Assembly.LoadFrom` and call `QueryPlanHelper.GetPlanFile` / `GetQueryFile` through expression evaluation; `src/EFCore.Visualizer.VSCode` (the VS Code extension) and `src/EFCore.Visualizer.Rider` (the Rider plugin) do this. Each has its own `AGENTS.md`.
- **`src/EFCore.Visualizer.VS/Vsix`** (project `EFCore.Visualizer`, net48, runs inside the VS process) is the Visual Studio extension. It uses VisualStudio.Extensibility in-process (`RequiresInProcessHosting = true`) together with the Community.VisualStudio.Toolkit.
  - `EFCoreQueryableDebuggerVisualizerProvider` registers the visualizer for `EntityQueryable<T>` and `IncludableQueryable<T,P>`. It names the debuggee-side object source by its assembly-qualified string name. The menu label ("Query Plan Visualizer") comes from `.vsextension/string-resources.json`.
  - `QueryPlanUserControl` is a WPF control that hosts WebView2. It sends two requests to the object source, `GetQuery` and then `GetQueryPlan`. Each reply is a path to a generated HTML file, which the control opens in WebView2. If the plan request fails, the control shows the error and displays the query-only page instead. It also passes the VS theme to the page: it sets `PreferredColorScheme` and injects the `--bg-color` CSS variable after navigation.
- **`src/EFCore.Visualizer.VS/ObjectSource`** (project `IQueryableObjectSource`, net6, loaded into the debuggee) is the Visual Studio object source, plus the Core files compiled in. It is installed into the VSIX under `netcoreapp\`.
  - `EFCoreQueryableObjectSource.TransferData` is a thin adapter over `QueryPlanHelper`. It reads a 1-byte `OperationType` request and writes the reply with `StreamExtensions`: a `bool isError` followed by a `string`, written with `BinaryWriter` and UTF-8. `QueryPlanUserControl.ReadString` reads the reply in the same format. If you change one side, change the other side too. Name the encoding on both sides: `Encoding.Default` is UTF-8 in the debuggee (.NET) but the ANSI code page in VS (.NET Framework).
  - `OperationType.cs` is linked into the VS project (`<Compile Include=... Link=...>`), so both sides use the same enum.
- **Inside `EFCore.Visualizer.Core`:**
  - `QueryPlanHelper.GetDatabaseProvider` selects a `DatabaseProvider` subclass by the `DbCommand` type's full name, given as a **string**. The project references only `Microsoft.EntityFrameworkCore.Relational` 7.0.0 and no provider packages, so provider code may use only `System.Data.Common` APIs.
  - Each provider runs its own plan query against the app's own connection and transaction. SQL Server, Postgres and Oracle **execute the query** to get an actual plan; SQLite does not:
    - SQL Server: `SET STATISTICS XML ON`
    - Postgres: `EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS)`
    - Oracle: `statistics_level = ALL`, then `DBMS_XPLAN.DISPLAY_CURSOR`
    - SQLite: `EXPLAIN QUERY PLAN`

    Providers that change session settings put them back in a `finally` block. The VS debugger stops an evaluation after about 5 seconds, so a slow plan query fails with a timeout. The README lists this as a known issue.
  - The plan is placed into `Resources/<Provider>/template.html`, which replaces the `{plan}` and `{query}` placeholders. The result is written as a new `EFCore.Visualizer-<random>.html` file in the temp folder. Before the data goes in, the template's relative `<script src>` and `<link href>` values are rewritten to absolute `file:` URLs of the template folder, so the page still finds its scripts and styles. Don't use a `<base>` for this: it would also redirect the page's own `#…` links, such as pev2's tabs. The VS side deletes that file when the control unloads. `GetPlanDirectory` returns the folder name, and `Encode` sets the escaping. The default escaping is JS string escaping with `JavaScriptEncoder.Default`, which also escapes `<`, quotes and backticks, because the query and plan contain data such as parameter values; templates must put `{plan}`/`{query}` only inside quoted JavaScript strings, never inside backtick strings, where `${` would still run. SQLite overrides it, because it creates the HTML tree itself.
  - `QueryPlanHelper` locates `Resources` relative to its own DLL: two directory levels up from the DLL, then `Resources`. Both extensions ship the templates that way: the VSIX packs `EFCore.Visualizer.Core\Resources\**` as `Resources\...` next to `netcoreapp\`, and the VS Code build copies them to `dist/Resources` next to `dist/netcoreapp`.

### Adding a database provider

1. Add a `DatabaseProvider` subclass in `EFCore.Visualizer.Core`.
2. Add its command type name to the `QueryPlanHelper.GetDatabaseProvider` switch.
3. Add a `Resources/<Name>/template.html` in `EFCore.Visualizer.Core`, plus any JS/CSS it needs. Shared assets are in `Resources/Common`. Both extensions pick up every file under `Resources`, so no project file needs a change.

## Release bookkeeping

The extension version is in `src/EFCore.Visualizer.VS/Vsix/source.extension.vsixmanifest` (`Identity Version`). The marketplace ID is `GiorgiDalakishvili.EFCoreVisualizer`. Third-party visualization libraries (pev2, html-query-plan, Treeflex, highlight.js) are vendored under `src/EFCore.Visualizer.Core/Resources/`. Their license texts, and those of everything they bundle (pev2's build includes all its dependencies except Vue), are in `Resources/ThirdPartyNotices.txt`, which both extensions ship. Regenerate it after updating a vendored library: set the versions in `tools/third-party-notices/package.json` and in the file list in its `generate.js`, then run `npm install --ignore-scripts` and `node generate.js` there. `src/EFCore.Visualizer.VS/Vsix/ThirdPartyLicenses/` has the older, partial licenses of pev2 and html-query-plan.
